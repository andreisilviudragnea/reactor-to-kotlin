package io.dragnea.reactor2kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.utils.addToStdlib.cast

fun KtNamedFunction.liftAwait() = processNameReferenceExpressions here@{
    it.liftAwaitElvisOperator() && return@here true

    it.liftAwaitLetBlock() && return@here true

    it.liftAwaitRunBlock() && return@here true

    it.liftAwaitIfExpression() && return@here true

    it.liftAwaitSimpleAssignment() && return@here true

    it.liftAwaitMonoMap() && return@here true

    it.liftAwaitMonoFlatMap(KtProperty::hasMonoFlatMapInitializer) && return@here true

    it.liftAwaitMonoFlatMap(KtProperty::hasMonoFlatMapManyInitializer) && return@here true

    it.liftAwaitMonoZip2() && return@here true

    it.liftAwaitMonoZip3() && return@here true

    it.liftAwaitMonoFilter() && return@here true

    it.liftAwaitMonoFilterWhen() && return@here true

    it.liftAwaitMonoSwitchIfEmpty() && return@here true

    it.liftAwaitMonoThenReturn() && return@here true

    it.liftAwaitMonoThen() && return@here true

    it.liftAwaitMonoDefer() && return@here true

    it.liftAwaitMonoJust() && return@here true

    it.liftAwaitMonoJustOrEmpty() && return@here true

    it.liftAwaitMonoEmpty() && return@here true

    it.liftAwaitMonoOnErrorReturn() && return@here true

    it.liftAwaitMonoOnErrorReturn1() && return@here true

    false
}

data class AwaitLiftingContext(
        val awaitCall: KtCallExpression,
        val ktProperty: KtProperty,
        val ktPsiFactory: KtPsiFactory
) {
    fun KtExpression.replaceWithAwaited(): PsiElement = replace(
            ktPsiFactory.createExpressionByPattern("$0?.$1", this, awaitCall)
    )

    fun KtFunctionLiteral.process() {
        processReturnExpressions {
            it.returnedExpression!!.replaceWithAwaited()
        }

        val lastStatement = bodyExpression!!.statements.last()

        if (lastStatement !is KtReturnExpression) {
            lastStatement.replaceWithAwaited()
        }
    }
}

data class OperatorAwaitLiftingContext(
        val ktNameReferenceExpression: KtNameReferenceExpression,
        val ktProperty: KtProperty,
        val awaitCall: KtQualifiedExpression,
        val anchor: PsiElement,
        val ktBlockExpression: KtBlockExpression,
        val ktPsiFactory: KtPsiFactory
) {
    fun String.createExpression() = ktPsiFactory.createExpression(this)

    fun String.createExpressionAsVariableInBlock() = createExpressionInBlock().introduceVariable()

    fun String.createExpressionInBlock(): KtExpression {
        val addedExpression = createExpression().addToBlock(ktBlockExpression, anchor)
        ktPsiFactory.createNewLine().addToBlock(ktBlockExpression, anchor)
        return addedExpression
    }

    fun String.createPropertyInBlock(): KtProperty {
        val addedProperty = ktPsiFactory
                .createProperty(this)
                .addToBlock(ktBlockExpression, anchor)
        ktPsiFactory.createNewLine().addToBlock(ktBlockExpression, anchor)
        return addedProperty
    }

    fun KtValueArgument.async() = "async { $text.awaitFirstOrNull() }".createExpressionAsVariableInBlock()

    fun KtProperty.await() = "$name.await()".createExpressionAsVariableInBlock()

    private fun KtExpression.replaceWithAwaited(): PsiElement = replace(
            ktPsiFactory.createExpressionByPattern("$0?.$1", this, awaitCall.selectorExpression!!)
    )

    fun KtFunctionLiteral.process() {
        processReturnExpressions {
            it.returnedExpression!!.replaceWithAwaited()
        }

        val lastStatement = bodyExpression!!.statements.last()

        if (lastStatement !is KtReturnExpression) {
            lastStatement.replaceWithAwaited()
        }
    }
}

private fun KtNameReferenceExpression.liftAwait(predicate: (KtProperty) -> Boolean, block: AwaitLiftingContext.() -> Unit): Boolean {
    val awaitCall = getAwaitFirstOrNullCall() ?: return false

    val ktProperty = resolve().castSafelyTo<KtProperty>() ?: return false

    if (!predicate(ktProperty)) return false

    AwaitLiftingContext(awaitCall, ktProperty, ktPsiFactory).block()

    parent.cast<KtQualifiedExpression>().replace(this)

    return true
}

private fun KtNameReferenceExpression.liftAwaitOperator(
        predicate: (KtProperty) -> Boolean,
        block: OperatorAwaitLiftingContext.() -> Unit
): Boolean {
    val awaitCall = getAwaitFirstOrNullCallIsReceiverOf() ?: return false

    val ktProperty = resolve().castSafelyTo<KtProperty>() ?: return false

    if (!predicate(ktProperty)) return false

    val anchor = awaitCall.selfOrParentInBlock()!!

    OperatorAwaitLiftingContext(
            this,
            ktProperty,
            awaitCall,
            anchor,
            anchor.parentOfType()!!,
            ktPsiFactory
    ).block()

    ktProperty.delete()

    return true
}

private fun KtProperty.hasElvisInitializer(): Boolean {
    val ktBinaryExpression = initializer.castSafelyTo<KtBinaryExpression>() ?: return false

    return ktBinaryExpression.isElvis()
}

private fun KtNameReferenceExpression.liftAwaitElvisOperator() = liftAwait(KtProperty::hasElvisInitializer) {
    val ktBinaryExpression = ktProperty.initializer.cast<KtBinaryExpression>()

    ktBinaryExpression.replace(ktPsiFactory.createExpressionByPattern(
            "$0?.awaitFirstOrNull() ?: $1.awaitFirstOrNull()",
            ktBinaryExpression.left!!,
            ktBinaryExpression.right!!
    ))
}

fun KtNameReferenceExpression.getLetKtProperty(): KtProperty? {
    val ktProperty = resolve().castSafelyTo<KtProperty>() ?: return null

    if (!ktProperty.hasLetInitializer()) return null

    return ktProperty
}

private fun KtProperty.hasLetInitializer(): Boolean {
    val ktQualifiedExpression = initializer.castSafelyTo<KtSafeQualifiedExpression>() ?: return false

    return ktQualifiedExpression.selectorExpression!!.isCallTo("let")
}

private fun KtProperty.hasRunInitializer(): Boolean {
    val ktCallExpression = initializer.castSafelyTo<KtCallExpression>() ?: return false

    return ktCallExpression.calleeExpression!!.isCallTo("run")
}

private fun KtExpression.isCallTo(name: String): Boolean {
    val ktNamedFunction = this
            .referenceExpression()!!
            .resolve()
            .castSafelyTo<KtNamedFunction>() ?: return false

    return ktNamedFunction.name == name
}

private fun KtNameReferenceExpression.liftAwaitLetBlock() = liftAwait(KtProperty::hasLetInitializer) {
    ktProperty.ktFunctionLiteral().process()
}

private fun KtNameReferenceExpression.liftAwaitRunBlock() = liftAwait(KtProperty::hasRunInitializer) {
    ktProperty.initializer.cast<KtCallExpression>().firstLambdaArgument().process()
}

private fun KtProperty.hasIfInitializer(): Boolean {
    initializer.castSafelyTo<KtIfExpression>() ?: return false
    return true
}

private fun AwaitLiftingContext.processExpression(ktExpression: KtExpression) {
    when (ktExpression) {
        is KtBlockExpression -> ktExpression.statements.last().replaceWithAwaited()
        else -> ktExpression.replaceWithAwaited()
    }
}

private fun KtNameReferenceExpression.liftAwaitIfExpression() = liftAwait(KtProperty::hasIfInitializer) {
    val ifExpression = ktProperty.initializer.cast<KtIfExpression>()

    processExpression(ifExpression.then!!)

    processExpression(ifExpression.`else`!!)
}

private fun KtProperty.hasSimpleInitializer(): Boolean {
    initializer.castSafelyTo<KtNameReferenceExpression>() ?: return false
    return true
}

private fun KtNameReferenceExpression.liftAwaitSimpleAssignment() = liftAwait(KtProperty::hasSimpleInitializer) {
    ktProperty.initializer!!.replaceWithAwaited()
}

private fun KtNameReferenceExpression.liftAwaitMonoMap() = liftAwaitOperator(KtProperty::hasMonoMapInitializer) {
    val letKtProperty = awaitReceiverAndExecuteLambdaBodyWithValue()

    ktNameReferenceExpression.parent.replace("${letKtProperty.name}".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoFlatMap(predicate: (KtProperty) -> Boolean) = liftAwaitOperator(predicate) {
    val receiverAwaitedKtProperty = awaitReceiverToProperty()

    val letKtProperty = executeLambdaBodyWithValueAndLiftAwait(receiverAwaitedKtProperty)

    ktNameReferenceExpression.parent.replace("${letKtProperty.name}".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoZip2() = liftAwaitOperator(KtProperty::hasMonoZip2Initializer) {
    val valueArguments = ktProperty.valueArguments()

    val p1Async = valueArguments[0].async()
    val p2Async = valueArguments[1].async()

    val p1Awaited = p1Async.await()
    val p2Awaited = p2Async.await()

    val p1AwaitedName = p1Awaited.name
    val p2AwaitedName = p2Awaited.name

    val lambdaExpression = valueArguments[2].getArgumentExpression().cast<KtLambdaExpression>()

    val valueParameterList = lambdaExpression.functionLiteral.valueParameterList!!
    valueParameterList.replace(ktPsiFactory.createParameterList("(${valueParameterList.text})"))

    ktNameReferenceExpression.parent.replace(
            "if ($p1AwaitedName != null && $p2AwaitedName != null) Pair($p1AwaitedName, $p2AwaitedName).let ${lambdaExpression.text} else null"
                    .createExpression()
    )
}

private fun KtNameReferenceExpression.liftAwaitMonoZip3() = liftAwaitOperator(KtProperty::hasMonoZip3Initializer) {
    val valueArguments = ktProperty.valueArguments()

    val p1Async = valueArguments[0].async()
    val p2Async = valueArguments[1].async()
    val p3Async = valueArguments[2].async()

    val p1Awaited = p1Async.await()
    val p2Awaited = p2Async.await()
    val p3Awaited = p3Async.await()

    val p1AwaitedName = p1Awaited.name
    val p2AwaitedName = p2Awaited.name
    val p3AwaitedName = p3Awaited.name

    ktNameReferenceExpression.parent.replace(
            "if ($p1AwaitedName != null && $p2AwaitedName != null && $p3AwaitedName != null) Tuples.of($p1AwaitedName, $p2AwaitedName, $p3AwaitedName) else null"
                    .createExpression()
    )
}

private fun KtProperty.valueArguments() = initializer
        .cast<KtQualifiedExpression>()
        .selectorExpression
        .cast<KtCallExpression>()
        .valueArguments

private fun KtNameReferenceExpression.liftAwaitMonoFilter() = liftAwaitOperator(KtProperty::hasMonoFilterInitializer) {
    val receiverAwaitedKtProperty = awaitReceiverToProperty()

    val letKtProperty = executeLambdaBodyWithValue(receiverAwaitedKtProperty)

    val ifKtProperty = "if (${letKtProperty.name} == true) ${receiverAwaitedKtProperty.name} else null".createExpressionAsVariableInBlock()

    ktNameReferenceExpression.parent.replace("${ifKtProperty.name}".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoFilterWhen() = liftAwaitOperator(KtProperty::hasMonoFilterWhenInitializer) {
    val receiverAwaitedKtProperty = awaitReceiverToProperty()

    val letKtProperty = executeLambdaBodyWithValueAndLiftAwait(receiverAwaitedKtProperty)

    val ifKtProperty = "if (${letKtProperty.name} == true) ${receiverAwaitedKtProperty.name} else null".createExpressionAsVariableInBlock()

    ktNameReferenceExpression.parent.replace("${ifKtProperty.name}".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoSwitchIfEmpty() = liftAwaitOperator(KtProperty::hasMonoSwitchIfEmptyInitializer) {
    val receiverAwaitedKtProperty = awaitReceiverToProperty()

    val name = receiverAwaitedKtProperty.name

    val ifKtProperty = "val ${ktProperty.name} = if ($name != null) Mono.just($name) else ${firstArgumentExpression().text}"
            .createPropertyInBlock()

    ifKtProperty
            .initializer
            .cast<KtIfExpression>()
            .then!!
            .introduceVariable()
}

private fun KtNameReferenceExpression.liftAwaitMonoThenReturn() = liftAwaitOperator(KtProperty::hasMonoThenReturnInitializer) {
    awaitReceiver()

    ktNameReferenceExpression.parent.replace(firstArgumentExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoThen() = liftAwaitOperator(KtProperty::hasMonoThenInitializer) {
    awaitReceiver()

    "val ${ktProperty.name} = ${firstArgumentExpression().text}".createPropertyInBlock()
}

private fun KtNameReferenceExpression.liftAwaitMonoDefer() = liftAwaitOperator(KtProperty::hasMonoDeferInitializer) {
    val parentInBlock = ktNameReferenceExpression.selfOrParentInBlock()!!

    val ktFunctionLiteral = ktProperty
            .initializer
            .cast<KtDotQualifiedExpression>()
            .selectorExpression
            .cast<KtCallExpression>()
            .firstLambdaArgument()

    val newProperty = "run ${ktFunctionLiteral.text}"
            .createExpression()
            .introduceVariableInBlock(parentInBlock.parent.cast(), parentInBlock)

    ktNameReferenceExpression.replace(newProperty.name!!.createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoJust() = liftAwaitOperator(KtProperty::hasMonoJustInitializer) {
    awaitCall.replace(firstArgumentExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoJustOrEmpty() = liftAwaitOperator(KtProperty::hasMonoJustOrEmptyInitializer) {
    awaitCall.replace(firstArgumentExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoEmpty() = liftAwaitOperator(KtProperty::hasMonoEmptyInitializer) {
    awaitCall.replace("null".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoOnErrorReturn() = liftAwaitOperator(KtProperty::hasMonoOnErrorReturnInitializer) {
    val valueArguments = ktProperty.valueArguments()

    val classNameReferenceExpression = valueArguments[0]
            .getArgumentExpression()
            .cast<KtQualifiedExpression>()
            .receiverExpression
            .cast<KtClassLiteralExpression>()
            .receiverExpression
            .cast<KtNameReferenceExpression>()

    ktNameReferenceExpression
            .parent
            .replace("try { ${ktProperty.receiverNameReferenceExpression().text}.awaitFirstOrNull() } catch (e: ${classNameReferenceExpression.text}) { ${valueArguments[1].text} }".createExpression())
}

private fun KtNameReferenceExpression.liftAwaitMonoOnErrorReturn1() = liftAwaitOperator(KtProperty::hasMonoOnErrorReturn1Initializer) {
    val valueArguments = ktProperty.valueArguments()

    ktNameReferenceExpression
            .parent
            .replace("try { ${ktProperty.receiverNameReferenceExpression().text}.awaitFirstOrNull() } catch (e: Throwable) { ${valueArguments[0].text} }".createExpression())
}

private fun OperatorAwaitLiftingContext.firstArgumentExpression(): KtExpression = ktProperty
        .valueArguments()[0]
        .getArgumentExpression()!!

private fun OperatorAwaitLiftingContext.awaitReceiverAndExecuteLambdaBodyWithValue(): KtProperty {
    val receiverAwaitedKtProperty = awaitReceiverToProperty()
    return executeLambdaBodyWithValue(receiverAwaitedKtProperty)
}

private fun OperatorAwaitLiftingContext.awaitReceiverToProperty(): KtProperty = awaitReceiver().introduceVariable()

private fun OperatorAwaitLiftingContext.awaitReceiver(): KtExpression =
        "${ktProperty.receiverNameReferenceExpression().text}.awaitFirstOrNull()".createExpressionInBlock()

private fun OperatorAwaitLiftingContext.executeLambdaBodyWithValue(receiverAwaitedKtProperty: KtProperty): KtProperty {
    val copiedInitializer = ktProperty
            .initializer!!
            .copied()
            .addToBlock(ktProperty.parentOfType()!!, ktProperty)

    val ktFunctionLiteral = copiedInitializer.cast<KtQualifiedExpression>().firstLambdaArgument()
    ktFunctionLiteral.setReturnLabelsToLet()

    val letKtProperty = "${receiverAwaitedKtProperty.name}?.let ${ktFunctionLiteral.text}".createExpressionAsVariableInBlock()

    copiedInitializer.delete()

    return letKtProperty
}

private fun OperatorAwaitLiftingContext.executeLambdaBodyWithValueAndLiftAwait(receiverAwaitedKtProperty: KtProperty): KtProperty {
    val copiedInitializer = ktProperty
            .initializer!!
            .copied()
            .addToBlock(ktProperty.parentOfType()!!, ktProperty)

    val ktFunctionLiteral = copiedInitializer.cast<KtQualifiedExpression>().firstLambdaArgument()
    ktFunctionLiteral.setReturnLabelsToLet()

    val letKtProperty = "${receiverAwaitedKtProperty.name}?.let ${ktFunctionLiteral.text}".createExpressionAsVariableInBlock()

    copiedInitializer.delete()

    letKtProperty
            .initializer
            .cast<KtQualifiedExpression>()
            .selectorExpression
            .cast<KtCallExpression>()
            .valueArguments[0]
            .getArgumentExpression()
            .cast<KtLambdaExpression>()
            .functionLiteral
            .process()

    return letKtProperty
}

fun KtFunctionLiteral.setReturnLabelsToLet() = processReturnExpressions {
    it.replace(ktPsiFactory.createExpressionByPattern("return@let $0", it.returnedExpression!!))
}

fun KtElement.selfOrParentInBlock(): PsiElement? {
    var current: PsiElement? = this
    while (true) {
        if (current == null) {
            return null
        }

        if (current.parent is KtBlockExpression) {
            return current
        }

        current = current.parent
    }
}
