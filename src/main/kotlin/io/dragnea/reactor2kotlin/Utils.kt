package io.dragnea.reactor2kotlin

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.refactoring.addTypeArgumentsIfNeeded
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.idea.refactoring.getQualifiedTypeArgumentList
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceVariable.KotlinIntroduceVariableHandler
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.bindingContextUtil.getTargetFunction
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

fun PsiMethod.returnsMono() = returnType
    .castSafelyTo<PsiClassType>()
    ?.resolve()
    ?.qualifiedName == "reactor.core.publisher.Mono"

fun KtNamedFunction.returnsMono() = toLightMethods()
    .map { it.returnsMono() }
    .all { it }

fun KtNamedFunction.extractPublisherReturningCallsToMethods() {
  var collectCallExpressions = collectCallExpressions()
  do {
    collectCallExpressions.first().introduceVariable()
    collectCallExpressions = collectCallExpressions()
  } while (collectCallExpressions.isNotEmpty())
}

private fun KtNamedFunction.collectCallExpressions(): List<KtExpression> {
  return collectDescendantsOfType<KtExpression>()
      .asSequence()
      .filter { it !is KtBlockExpression }
      .filter { it !is KtNameReferenceExpression }
      .filter { it.parent.castSafelyTo<KtDotQualifiedExpression>()?.selectorExpression != it }
      .filter {
        val parent = it.parent
        !(parent is KtProperty && parent.initializer == it)
      }
      .filter {
        it
            .analyze()
            .getType(it)
            ?.fqName
            .toString() in listOf(
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux"
        )
      }
      .toList()
}

val PsiElement.elementFactory: PsiElementFactory get() = JavaPsiFacade.getElementFactory(project)
val PsiElement.ktPsiFactory: KtPsiFactory get() = KtPsiFactory(this)

fun KtNamedFunction.wrapIntoMonoCoroutineBuilder() {
  val ktNamedFunction = this
  KtPsiFactory(ktNamedFunction).run {
    val monoExpression = createExpression("kotlinx.coroutines.reactor.mono {}")

    val callExpression = monoExpression
        .cast<KtDotQualifiedExpression>()
        .selectorExpression
        .cast<KtCallExpression>()

    callExpression
        .lambdaArguments
        .single()
        .delete()

    val bodyBlockExpression = bodyBlockExpression!!

    ktNamedFunction.processReturnExpressions {
      it.replace(createExpressionByPattern("return@mono $0.awaitFirstOrNull()", it.returnedExpression!!))
    }

    val dummyCall = createExpressionByPattern("foo()$0:'{}'", bodyBlockExpression) as KtCallExpression
    val functionLiteralArgument = dummyCall.lambdaArguments.single()
    callExpression.add(functionLiteralArgument)

    addBefore(createEQ(), bodyBlockExpression)

    ShortenReferences.DEFAULT.process(bodyBlockExpression.replaced(monoExpression))
  }
}

val PsiMethodCallExpression.firstArgument: PsiExpression get() = argumentList.expressions[0]

fun PsiMethodCallExpression.getNotDeferredArgument(): PsiExpression? {
  if (!isThenCall()) return null

  when (val firstArgument = firstArgument) {
    is PsiMethodCallExpression -> {
      return firstArgument.getCallIfNotDefer()
    }
    is PsiReferenceExpression -> {
      val psiLocalVariable = firstArgument.resolve().castSafelyTo<PsiLocalVariable>() ?: return null
      val psiMethodCallExpression = psiLocalVariable.initializer.castSafelyTo<PsiMethodCallExpression>()
          ?: return null
      return psiMethodCallExpression.getCallIfNotDefer()
    }
    else -> return null
  }
}

fun PsiMethodCallExpression.getThenJustArgument(): PsiExpression? {
  if (!isThenCall()) return null

  return when (val firstArgument = firstArgument) {
    is PsiMethodCallExpression -> {
      if (!firstArgument.isCallTo("public static <T> reactor.core.publisher.Mono<T> just(T data) { return null; }")) {
        return null
      }

      return firstArgument
    }
    else -> null
  }
}

private fun PsiMethodCallExpression.getCallIfNotDefer(): PsiExpression? {
  if (isCallTo("public static <T> reactor.core.publisher.Mono<T> defer(java.util.function.Supplier<? extends reactor.core.publisher.Mono<? extends T>> supplier) { return null; }")) {
    return null
  }
  return this
}

inline fun <S: PsiElement, reified T: PsiElement> S.process(block: S.(T) -> Boolean) {
  var ts = collectDescendantsOfType<T>()
  while (processedAtLeastOnce(ts, block)) {
    ts = collectDescendantsOfType()
  }
}

inline fun <S, T> S.processedAtLeastOnce(ts: List<T>, block: S.(T) -> Boolean): Boolean {
  ts.forEach { t ->
    if (block(t)) {
      return true
    }
  }
  return false
}

fun KtNamedFunction.processNameReferenceExpressions(block: KtNamedFunction.(KtNameReferenceExpression) -> Boolean) = process(block)

fun KtExpression.introduceVariable(): KtProperty {
  var ktProperty: KtProperty? = null

  val typeArgumentList = getQualifiedTypeArgumentList(KtPsiUtil.safeDeparenthesize(this))

  KotlinIntroduceVariableHandler.doRefactoring(
      project,
      null,
      this,
      false,
      listOf(this)
  ) { ktProperty = it.cast()

    if (typeArgumentList != null) {
      runWriteAction { addTypeArgumentsIfNeeded(ktProperty!!.initializer!!, typeArgumentList) }
    }
  }

  return ktProperty!!
}

inline fun <reified T: PsiElement> T.addToBlock(block: KtBlockExpression, anchor: PsiElement): T =
        block.addBefore(this, anchor).cast()

fun KtExpression.introduceVariableInBlock(block: KtBlockExpression, anchor: PsiElement): KtProperty {
  return addToBlock(block, anchor).introduceVariable()
}

fun KtProperty.receiverNameReferenceExpression(): KtNameReferenceExpression = initializer
    .cast<KtDotQualifiedExpression>()
    .receiverExpression
    .cast()

fun KtQualifiedExpression.firstLambdaArgument(): KtFunctionLiteral = this
        .selectorExpression
        .cast<KtCallExpression>()
        .firstLambdaArgument()

fun KtCallExpression.firstLambdaArgument(): KtFunctionLiteral = valueArguments
        .map { it.getArgumentExpression() }
        .firstIsInstance<KtLambdaExpression>()
        .functionLiteral

fun KtProperty.ktFunctionLiteral(): KtFunctionLiteral = initializer
    .cast<KtQualifiedExpression>()
    .firstLambdaArgument()

fun KtFunction.processReturnExpressions(block: (KtReturnExpression) -> Unit) {
  val bindingContext = analyze()

  collectDescendantsOfType<KtReturnExpression>().forEach {
    if (it.getTargetFunction(bindingContext) == this) {
      block(it)
    }
  }
}

fun KtExpressionWithLabel.labelString(): String {
  val labelName = getLabelName()
  return if (labelName != null) "@$labelName" else ""
}

fun KtNameReferenceExpression.getKtQualifiedExpressionIsReceiverOf(): KtQualifiedExpression? {
  val ktQualifiedExpression = parent.castSafelyTo<KtQualifiedExpression>() ?: return null
  return if (ktQualifiedExpression.receiverExpression == this) ktQualifiedExpression else null
}

fun KtNameReferenceExpression.getAwaitFirstOrNullCall(): KtCallExpression? {
  val ktQualifiedExpression = getKtQualifiedExpressionIsReceiverOf() ?: return null

  val ktCallExpression = ktQualifiedExpression.selectorExpression.castSafelyTo<KtCallExpression>() ?: return null

  if (ktCallExpression.calleeExpression.cast<KtNameReferenceExpression>().getReferencedName() != "awaitFirstOrNull") return null

  return ktCallExpression
}

fun KtNameReferenceExpression.getAwaitFirstOrNullCallIsReceiverOf(): KtQualifiedExpression? {
  val ktQualifiedExpression = getKtQualifiedExpressionIsReceiverOf() ?: return null

  val ktCallExpression = ktQualifiedExpression.selectorExpression.castSafelyTo<KtCallExpression>() ?: return null

  if (ktCallExpression.calleeExpression.cast<KtNameReferenceExpression>().getReferencedName() != "awaitFirstOrNull") return null

  return ktQualifiedExpression
}

fun KtBinaryExpression.isElvis() = operationToken == KtTokens.ELVIS

fun KtFunctionLiteral.firstParameter(): KtParameter = valueParameters[0]
