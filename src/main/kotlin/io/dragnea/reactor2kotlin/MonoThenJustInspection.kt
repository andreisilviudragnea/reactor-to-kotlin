package io.dragnea.reactor2kotlin

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.utils.addToStdlib.cast

// TODO: Fix signature clash with Flux.then()
class MonoThenJustInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val psiElement = expression.getThenJustArgument() ?: return
                holder.registerProblem(
                        psiElement,
                        "Mono then just",
                        ProblemHighlightType.WARNING,
                        Fix()
                )
            }
        }
    }

    class Fix : LocalQuickFix {
        override fun getFamilyName() = "Mono then just"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val callToJust = descriptor.psiElement.cast<PsiMethodCallExpression>()
            val callToThen = callToJust.parentOfType<PsiMethodCallExpression>()!!
            callToThen.replace(callToJust
                    .elementFactory
                    .createExpressionFromText(
                            "${callToThen.methodExpression.qualifierExpression!!.text}.thenReturn(${callToJust.firstArgument.text})",
                            callToJust
                    ))
        }
    }
}
