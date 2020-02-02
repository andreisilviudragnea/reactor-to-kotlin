package io.dragnea.reactor2kotlin

import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.rename.NameSuggestionProvider
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithAllCompilerChecks
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.core.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.core.NewDeclarationNameValidator
import org.jetbrains.kotlin.idea.core.dropEnclosingParenthesesIfPossible
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.findUsages.ReferencesSearchScopeHelper
import org.jetbrains.kotlin.idea.refactoring.inline.KotlinInlineValHandler
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.utils.addToStdlib.cast

fun KtNamedFunction.optimizeCode() {
    fixUselessElvisOperator()

    inlineExactCopiesOfVariables()

    processLetBlocksBeforeReturns()

    // TODO: Process run blocks before returns

    makeNullableLetReturnEarly()

//    inlineValuesWithOneUsage()
}

/**
 * See [org.jetbrains.kotlin.idea.quickfix.RemoveUselessElvisFix]
 */
private fun KtNamedFunction.fixUselessElvisOperator() = process<KtNamedFunction, KtBinaryExpression> { ktBinaryExpression ->
    analyzeWithAllCompilerChecks()
            .bindingContext
            .diagnostics
            .forElement(ktBinaryExpression)
            .firstOrNull {
                it.factory in listOf(
                        // TODO: This might not be needed
                        Errors.USELESS_ELVIS,
                        Errors.USELESS_ELVIS_RIGHT_IS_NULL
                )
            }
            ?: return@process false

    runWriteAction { dropEnclosingParenthesesIfPossible(ktBinaryExpression.replaced(ktBinaryExpression.left!!)) }
    true
}

private fun KtExpression.getReturnExpressionWhichReturnsThis(): KtReturnExpression? {
    val ktReturnExpression = parent.castSafelyTo<KtReturnExpression>() ?: return null
    return if (ktReturnExpression.returnedExpression == this) ktReturnExpression else null
}

private fun KtNamedDeclaration.rename(newName: String) = RenameProcessor(project, this, newName, false, false).run()

// TODO: Also rename local variables if they shadow names
private fun KtFunctionLiteral.renameIfItShadowsName(ktParameter: KtParameter): Boolean {
    analyze()
            .diagnostics
            .forElement(ktParameter)
            .firstOrNull { it.factory == Errors.NAME_SHADOWING } ?: return false

    val names = mutableSetOf<String>()
    NameSuggestionProvider
            .EP_NAME
            .findExtensionOrFail(KotlinNameSuggestionProvider::class.java)
            .getSuggestedNames(ktParameter, ktParameter, names)

    ktParameter.rename(names.first())

    return true
}

private fun KtNamedFunction.processLetBlocksBeforeReturns() = processNameReferenceExpressions {
    val ktReturnExpression = it.getReturnExpressionWhichReturnsThis() ?: return@processNameReferenceExpressions false
    val letKtProperty = it.getLetKtProperty() ?: return@processNameReferenceExpressions false

    val nullableLetCall = letKtProperty.initializer.cast<KtQualifiedExpression>()

    val ktFunctionLiteral = nullableLetCall.firstLambdaArgument()

    val ktParameter = ktFunctionLiteral.firstParameter()

    if (ktFunctionLiteral.renameIfItShadowsName(ktParameter)) {
        return@processNameReferenceExpressions true
    }

    runWriteAction {
        val ktPsiFactory = ktPsiFactory

        val ktBlockExpression: KtBlockExpression = ktReturnExpression.parentOfType()!!

        val letReceiverKtProperty = ktPsiFactory
                .createExpressionByPattern(
                        "$0 ?: return${ktReturnExpression.labelString()} null",
                        nullableLetCall.receiverExpression
                )
                .introduceVariableInBlock(ktBlockExpression, ktReturnExpression)

        ktBlockExpression.addBefore(ktPsiFactory.createNewLine(), ktReturnExpression)

        ktFunctionLiteral.processReturnExpressions {
            it.replace(ktPsiFactory.createExpressionByPattern(
                    "return${ktReturnExpression.labelString()} $0",
                    it.returnedExpression!!
            ))
        }

        val destructuringDeclaration = ktFunctionLiteral.valueParameters[0].destructuringDeclaration

        if (destructuringDeclaration == null) {
            letReceiverKtProperty.identifyingElement!!.replace(ktPsiFactory.createIdentifier(ktParameter.name!!))
        } else {
            ktPsiFactory
                    .createDestructuringDeclaration("val ${destructuringDeclaration.text} = ${letReceiverKtProperty.name}")
                    .addToBlock(ktBlockExpression, ktReturnExpression)
            ktBlockExpression.addBefore(ktPsiFactory.createNewLine(), ktReturnExpression)
        }

        val statements = ktFunctionLiteral
                .bodyExpression!!
                .statements

        ktBlockExpression.addRangeBefore(statements.first(), statements.last(), ktReturnExpression)

        letKtProperty.delete()

        ktReturnExpression.delete()

        true
    }
}

private fun KtFunctionLiteral.hasReturns(): Boolean {
    var hasReturns = false

    processReturnExpressions {
        hasReturns = true
    }

    return hasReturns
}

private fun KtNameReferenceExpression.hasElvisParentWithNullEarlyReturn(): Boolean {
    val binaryExpression = parent.castSafelyTo<KtBinaryExpression>() ?: return false

    if (!binaryExpression.isElvis()) return false

    if (binaryExpression.left != this) return false

    val ktReturnExpression = binaryExpression.right.castSafelyTo<KtReturnExpression>() ?: return false

    return ktReturnExpression.returnedExpression?.text == "null"
}

private fun KtProperty.suggestNameByName(name: String): String = KotlinNameSuggester.suggestNameByName(
        name,
        NewDeclarationNameValidator(
            container = getStrictParentOfType<KtDeclaration>()!!,
            anchor = this,
            target = NewDeclarationNameValidator.Target.VARIABLES
    )
)

private fun KtNamedFunction.makeNullableLetReturnEarly() = processNameReferenceExpressions {
    if (!it.hasElvisParentWithNullEarlyReturn()) return@processNameReferenceExpressions false

    val letKtProperty = it.getLetKtProperty() ?: return@processNameReferenceExpressions false

    val nullableLetCall = letKtProperty.initializer.cast<KtQualifiedExpression>()

    val ktFunctionLiteral = nullableLetCall.firstLambdaArgument()

    if (ktFunctionLiteral.hasReturns()) {
        return@processNameReferenceExpressions false
    }

    var letReceiverKtProperty: KtProperty? = null

    val ktPsiFactory = ktPsiFactory

    val ktBlockExpression: KtBlockExpression = letKtProperty.parentOfType()!!

    runWriteAction {
        letReceiverKtProperty = ktPsiFactory
                .createExpressionByPattern(
                        "$0 ?: return@mono null",
                        nullableLetCall.receiverExpression
                )
                .introduceVariableInBlock(ktBlockExpression, letKtProperty)

        ktBlockExpression.addBefore(ktPsiFactory.createNewLine(), letKtProperty)
    }

    val firstParameter = ktFunctionLiteral.firstParameter()
    val firstParameterName = firstParameter.name!!

    val capturedLetReceiverKtProperty = letReceiverKtProperty!!
    firstParameter.rename(capturedLetReceiverKtProperty.name!!)

    runWriteAction {
        val statements = ktFunctionLiteral.bodyExpression!!.statements

        if (statements.size >= 2) {
            ktBlockExpression.addRangeBefore(statements.first(), statements[statements.size - 2], letKtProperty)
        }

        ktBlockExpression.addBefore(
                ktPsiFactory.createProperty("val ${letKtProperty.name} = ${statements.last().text}"),
                letKtProperty
        )

        letKtProperty.delete()

        it.parent.cast<KtBinaryExpression>().replace(it)
    }

    capturedLetReceiverKtProperty.rename(capturedLetReceiverKtProperty.suggestNameByName(firstParameterName))

    true
}

private fun KtNamedFunction.inlineValuesWithOneUsage() = collectDescendantsOfType<KtProperty>().forEach {
    if (ReferencesSearchScopeHelper.search(it).findAll().size == 1) {
        KotlinInlineValHandler(withPrompt = false).inlineElement(project, null, it)
    }
}
