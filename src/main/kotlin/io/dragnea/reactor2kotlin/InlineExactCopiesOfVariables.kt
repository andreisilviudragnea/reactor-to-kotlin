package io.dragnea.reactor2kotlin

import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.NewDeclarationNameValidator
import org.jetbrains.kotlin.idea.refactoring.inline.KotlinInlinePropertyHandler
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

/**
 * Copied from [org.jetbrains.kotlin.idea.inspections.UnnecessaryVariableInspection]
 */
private fun KtProperty.isExactCopy(): Boolean {
    val enclosingElement = KtPsiUtil.getEnclosingElementForLocalDeclaration(this) ?: return false
    val initializer = initializer ?: return false

    if (!isVar && initializer is KtNameReferenceExpression && typeReference == null) {
        val initializerDescriptor =
            initializer.resolveToCall(BodyResolveMode.FULL)?.resultingDescriptor as? VariableDescriptor
                ?: return false
        if (initializerDescriptor.isVar) return false
        if (initializerDescriptor.containingDeclaration !is FunctionDescriptor) return false

        val copyName = initializerDescriptor.name.asString()
        if (ReferencesSearch.search(this, LocalSearchScope(enclosingElement)).findFirst() == null) return false

        val containingDeclaration = getStrictParentOfType<KtDeclaration>()
        if (containingDeclaration != null) {
            val validator =
                NewDeclarationNameValidator(
                    container = containingDeclaration,
                    anchor = this,
                    target = NewDeclarationNameValidator.Target.VARIABLES,
                    excludedDeclarations =
                        listOfNotNull(
                            DescriptorToSourceUtils.descriptorToDeclaration(initializerDescriptor) as? KtDeclaration,
                        ),
                )
            if (!validator(copyName)) return false
        }
        return true
    }
    return false
}

fun KtNamedFunction.inlineExactCopiesOfVariables() =
    process<KtNamedFunction, KtProperty> {
        if (!it.isExactCopy()) {
            return@process false
        }

        KotlinInlinePropertyHandler(withPrompt = false).inlineElement(project, null, it)
        true
    }
