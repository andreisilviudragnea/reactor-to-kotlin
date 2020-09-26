package io.dragnea.reactor2kotlin.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.util.parentOfType
import io.dragnea.reactor2kotlin.extractPublisherReturningCallsToMethods
import io.dragnea.reactor2kotlin.liftAwait
import io.dragnea.reactor2kotlin.optimizeCode
import io.dragnea.reactor2kotlin.returnsMono
import io.dragnea.reactor2kotlin.wrapInMono
import org.jetbrains.kotlin.idea.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.util.ImportInsertHelperImpl
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.namedFunctionVisitor

// TODO: Create Intellij Java inspections which cleanup reactor code
// TODO: Create MonoSwitchIfEmpty inspection
// TODO: Create inspection for AssertJ isEqualToComparingFieldByField() when comparing exceptions
// TODO: Create Reactor Hook for emptiness detection
// TODO: Implement Kotlin flip if condition intention
class ExtractMonoReturningCallsToVariablesInspection : AbstractKotlinInspection() {
    override fun buildVisitor(
            holder: ProblemsHolder,
            isOnTheFly: Boolean
    ) = namedFunctionVisitor { ktNamedFunction ->
        ktNamedFunction.returnsMono() || return@namedFunctionVisitor

        holder.registerProblem(
            ktNamedFunction.nameIdentifier!!,
            "Function returns Mono",
            ProblemHighlightType.WARNING,
            Fix()
        )
    }

    class Fix : LocalQuickFix {
        override fun startInWriteAction() = false

        override fun getFamilyName() = "Process function"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            with(descriptor.psiElement.parentOfType<KtNamedFunction>()!!) {
                runWriteAction {
                    ImportInsertHelperImpl.addImport(project, containingKtFile, FqName("kotlinx.coroutines.async"))
                    ImportInsertHelperImpl.addImport(project, containingKtFile, FqName("kotlinx.coroutines.reactive.awaitSingle"))
                    ImportInsertHelperImpl.addImport(project, containingKtFile, FqName("kotlinx.coroutines.reactive.awaitFirstOrNull"))
                    ImportInsertHelperImpl.addImport(project, containingKtFile, FqName("reactor.util.function.Tuples"))

                    extractPublisherReturningCallsToMethods()

//                    wrapIntoMonoCoroutineBuilder()

                    wrapInMono()
                }

                runWriteAction {
                    liftAwait()
                }

                optimizeCode()
            }
        }
    }
}
