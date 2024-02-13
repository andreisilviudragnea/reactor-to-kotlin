package io.dragnea.reactor2kotlin.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethodCallExpression
import io.dragnea.reactor2kotlin.elementFactory
import io.dragnea.reactor2kotlin.getNotDeferredArgument
import org.jetbrains.kotlin.utils.addToStdlib.cast

class MonoThenInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val psiElement = expression.getNotDeferredArgument() ?: return
                holder.registerProblem(
                    psiElement,
                    "Mono then",
                    ProblemHighlightType.WARNING,
                    Fix(),
                )
            }
        }
    }

    class Fix : LocalQuickFix {
        override fun getFamilyName() = "Mono then"

        override fun applyFix(
            project: Project,
            descriptor: ProblemDescriptor,
        ) {
            val psiExpression = descriptor.psiElement.cast<PsiExpression>()

            val createExpressionFromText =
                psiExpression.elementFactory.createExpressionFromText(
                    "reactor.core.publisher.Mono.defer(() -> ${psiExpression.text})",
                    psiExpression,
                )

            psiExpression.replace(createExpressionFromText)
        }
    }
}
