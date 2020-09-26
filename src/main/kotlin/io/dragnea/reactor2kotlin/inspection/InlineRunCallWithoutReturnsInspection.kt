package io.dragnea.reactor2kotlin.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentOfType
import io.dragnea.reactor2kotlin.inlineRunWithoutReturns
import io.dragnea.reactor2kotlin.isRunWithoutReturns
import io.dragnea.reactor2kotlin.renameFirstRedeclaration
import org.jetbrains.kotlin.idea.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.callExpressionVisitor

class InlineRunCallWithoutReturnsInspection: AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = callExpressionVisitor {
        it.isRunWithoutReturns() || return@callExpressionVisitor

        holder.registerProblem(
            it.calleeExpression!!,
            "Run call without returns",
            ProblemHighlightType.WARNING,
            Fix()
        )
    }

    class Fix : LocalQuickFix {
        override fun startInWriteAction() = false

        override fun getFamilyName() = "Inline run call without returns"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            descriptor.psiElement.parentOfType<KtCallExpression>()!!.inlineRunWithoutReturnsAndRenameRedeclarations()
        }
    }
}

fun KtCallExpression.inlineRunWithoutReturnsAndRenameRedeclarations() {
    val ktBlockExpression = runWriteAction {
        inlineRunWithoutReturns()!!
    }

    while (true) {
        if (!ktBlockExpression.renameFirstRedeclaration()) {
            break
        }
    }
}
