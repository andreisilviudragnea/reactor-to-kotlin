package io.dragnea.reactor2kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.rename.NameSuggestionProvider
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.analyzeAndGetResult
import org.jetbrains.kotlin.idea.core.KotlinNameSuggester
import org.jetbrains.kotlin.idea.core.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.core.NewDeclarationNameValidator
import org.jetbrains.kotlin.idea.core.dropEnclosingParenthesesIfPossible
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.findUsages.ReferencesSearchScopeHelper
import org.jetbrains.kotlin.idea.inspections.UseExpressionBodyInspection
import org.jetbrains.kotlin.idea.refactoring.inline.KotlinInlinePropertyHandler
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
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
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.utils.addToStdlib.cast

fun KtNamedFunction.optimizeCode() {
    // TODO: Process run blocks before returns
    removeRunsWithoutReturns()

    fixUselessElvisOperator()

    inlineExactCopiesOfVariables()

    processLetBlocksBeforeReturns()

    makeNullableLetReturnEarly()

    inlineValuesWithOneUsage()

    runWriteAction {
        val useExpressionBodyInspection = UseExpressionBodyInspection()
        if (useExpressionBodyInspection.isActiveFor(this)) {
            useExpressionBodyInspection.simplify(this, false)
        }
    }
}

fun KtCallExpression.isRunWithoutReturns(): Boolean {
    isRunCall() || return false

    val functionLiteral = this
        .lambdaArguments[0]
        .getLambdaExpression()!!
        .functionLiteral

    !functionLiteral.hasReturns() || return false

    return true
}

val KtCallExpression.statementsFromFirstLambdaArgument: List<KtExpression>
    get() = this
        .lambdaArguments[0]
        .getLambdaExpression()!!
        .functionLiteral
        .bodyExpression!!
        .statements

fun KtNamedFunction.removeRunsWithoutReturns() = process<KtNamedFunction, KtCallExpression> {
    it.isRunWithoutReturns() || return@process false

    val ktBlockExpression = runWriteAction { it.inlineRunWithoutReturns() } ?: return@process false

    ktBlockExpression.renameAllRedeclarations()
}

fun KtCallExpression.inlineRunWithoutReturns(): KtBlockExpression? {
    when (val parent = parent) {
        is KtProperty -> {
            parent.initializer == this || return null

            val ktBlockExpression = parent.parentOfType<KtBlockExpression>()!!

            val statements = statementsFromFirstLambdaArgument

            if (statements.size >= 2) {
                ktBlockExpression.addRangeBefore(statements.first(), statements[statements.size - 2], parent)
            }

            ktBlockExpression.addBefore(ktPsiFactory.createNewLine(), parent)

            ktBlockExpression.addBefore(
                ktPsiFactory.createProperty("val ${parent.name} = ${statements.last().text}"),
                parent
            )

            parent.delete()

            return ktBlockExpression
        }
        is KtBlockExpression -> {
            val statements = statementsFromFirstLambdaArgument

            parent.addRangeBefore(statements.first(), statements.last(), this)

            delete()

            return parent
        }
        else -> return null
    }
}

/**
 * See [org.jetbrains.kotlin.idea.quickfix.RemoveUselessElvisFix]
 */
private fun KtNamedFunction.fixUselessElvisOperator() = process<KtNamedFunction, KtBinaryExpression> { ktBinaryExpression ->
    analyzeAndGetResult()
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

fun KtNamedDeclaration.rename(newName: String) = RenameProcessor(project, this, newName, false, false).run()

fun PsiElement.suggestNames(): Set<String> {
    val names = mutableSetOf<String>()

    NameSuggestionProvider
        .EP_NAME
        .findExtensionOrFail(KotlinNameSuggestionProvider::class.java)
        .getSuggestedNames(this, this, names)

    return names
}

// TODO: Also rename local variables if they shadow names
private fun KtFunctionLiteral.renameIfItShadowsName(ktParameter: KtParameter): Boolean {
    analyze()
            .diagnostics
            .forElement(ktParameter)
            .firstOrNull { it.factory == Errors.NAME_SHADOWING } ?: return false

    val names = ktParameter.suggestNames()

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

fun KtFunctionLiteral.hasReturns(): Boolean {
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
    if (ReferencesSearchScopeHelper.search(it).findAll().size == 1 && !it.hasAsyncInitializer()) {
        KotlinInlinePropertyHandler(withPrompt = false).inlineElement(project, null, it)
    }
}

fun KtProperty.hasAsyncInitializer(): Boolean {
    val ktCallExpression = initializer.castSafelyTo<KtCallExpression>() ?: return false

    return ktCallExpression.calleeExpression!!.isCallTo("async")
}

fun KtBlockExpression.renameAllRedeclarations(): Boolean {
    var done = renameFirstRedeclaration()
    var atLeastOnce = done

    while (done) {
        done = renameFirstRedeclaration()
        atLeastOnce = atLeastOnce || done
    }

    return atLeastOnce
}

fun KtBlockExpression.renameFirstRedeclaration(): Boolean {
    val ktProperties = collectDescendantsOfType<KtProperty>()

    val diagnostics = analyze().diagnostics

    ktProperties.forEach {
        if (it.renameIfRedeclaration(diagnostics)) {
            return true
        }
    }

    return false
}

fun KtProperty.renameIfRedeclaration(diagnostics: Diagnostics): Boolean {
    diagnostics
        .forElement(this)
        .firstOrNull { it.factory == Errors.REDECLARATION } ?: return false

    val names = suggestNames()

    rename(names.first())

    return true
}
