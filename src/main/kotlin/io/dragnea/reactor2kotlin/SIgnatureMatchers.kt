package io.dragnea.reactor2kotlin

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.util.castSafelyTo
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

fun KtProperty.hasMonoMapInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <R> reactor.core.publisher.Mono<R> map(java.util.function.Function<? super T, ? extends R> mapper) { return null; }")
}

fun KtProperty.hasMonoFlatMapInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <R> reactor.core.publisher.Mono<R> flatMap(java.util.function.Function<? super T, ? extends reactor.core.publisher.Mono<? extends R>> transformer) { return null; }")
}

fun KtProperty.hasMonoFlatMapManyInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <R> reactor.core.publisher.Flux<R> flatMapMany(java.util.function.Function<? super T, ? extends org.reactivestreams.Publisher<? extends R>> mapper) { return null; }")
}

fun KtProperty.hasMonoFilterInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final reactor.core.publisher.Mono<T> filter(final java.util.function.Predicate<? super T> tester) { return null; }")
}

fun KtProperty.hasMonoFilterWhenInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final reactor.core.publisher.Mono<T> filterWhen(java.util.function.Function<? super T, ? extends org.reactivestreams.Publisher<java.lang.Boolean>> asyncPredicate) { return null; }")
}

fun KtProperty.hasMonoSwitchIfEmptyInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final reactor.core.publisher.Mono<T> switchIfEmpty(reactor.core.publisher.Mono<? extends T> alternate) { return null; }")
}

fun KtProperty.hasMonoZip2Initializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T1, T2, O> reactor.core.publisher.Mono<O> zip(reactor.core.publisher.Mono<? extends T1> p1, reactor.core.publisher.Mono<? extends T2> p2, java.util.function.BiFunction<? super T1, ? super T2, ? extends O> combinator) { return null; }")
}

fun KtProperty.hasMonoZip3Initializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T1, T2, T3> reactor.core.publisher.Mono<reactor.util.function.Tuple3<T1, T2, T3>> zip(reactor.core.publisher.Mono<? extends T1> p1, reactor.core.publisher.Mono<? extends T2> p2, reactor.core.publisher.Mono<? extends T3> p3) { return null; }")
}

fun KtProperty.hasMonoThenReturnInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <V> reactor.core.publisher.Mono<V> thenReturn(V value) { return null; }")
}

fun KtProperty.hasMonoThenInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <V> reactor.core.publisher.Mono<V> then(reactor.core.publisher.Mono<V> other) { return null; }")
}

fun KtProperty.hasMonoJustInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T> reactor.core.publisher.Mono<T> just(T data) { return null; }")
}

fun KtProperty.hasMonoJustOrEmptyInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T> reactor.core.publisher.Mono<T> justOrEmpty(T data) { return null; }")
}

fun KtProperty.hasMonoEmptyInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T> reactor.core.publisher.Mono<T> empty() { return null; }")
}

fun KtProperty.hasMonoDeferInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public static <T> reactor.core.publisher.Mono<T> defer(java.util.function.Supplier<? extends reactor.core.publisher.Mono<? extends T>> supplier) { return null; }")
}

fun KtProperty.hasMonoOnErrorReturnInitializer(): Boolean {
    return hasMonoInitializerWithSignature("public final <E extends java.lang.Throwable> reactor.core.publisher.Mono<T> onErrorReturn(java.lang.Class<E> type, T fallbackValue) { return null; }")
}

fun KtProperty.hasMonoOnErrorReturn1Initializer(): Boolean {
    return hasMonoInitializerWithSignature("public final reactor.core.publisher.Mono<T> onErrorReturn(final T fallback) { return null; }")
}

private fun KtProperty.hasMonoInitializerWithSignature(signature: String) =
    hasInitializerWithSignature("reactor.core.publisher.Mono", signature)

private fun KtProperty.hasInitializerWithSignature(className: String, signature: String): Boolean {
    val initializer = initializer.castSafelyTo<KtDotQualifiedExpression>() ?: return false

    val ktCallExpression = initializer
        .selectorExpression
        .castSafelyTo<KtCallExpression>() ?: return false

    val method = ktCallExpression
        .referenceExpression()!!
        .resolve()
        .castSafelyTo<PsiMethod>() ?: return false

    if (method.containingClass!!.qualifiedName != className) {
        return false
    }

    if (!method.hasEqualSignatureTo(signature)) {
        return false
    }

    return true
}

fun PsiMethod.hasEqualSignatureTo(signature: String): Boolean {
    return MethodSignatureUtil.areSignaturesEqual(
        getSignature(PsiSubstitutor.EMPTY),
        elementFactory.createMethodFromText(signature, this).getSignature(PsiSubstitutor.EMPTY)
    )
}

fun PsiMethodCallExpression.isCallTo(signature: String): Boolean {
    val resolveMethod = resolveMethod() ?: return false
    return resolveMethod.hasEqualSignatureTo(signature)
}

fun PsiMethodCallExpression.isThenCall(): Boolean {
    return isCallTo("public final <V> reactor.core.publisher.Mono<V> then(reactor.core.publisher.Mono<V> other) { return null; }")
}
