package com.github.projectstats

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.siyeh.ig.classmetrics.CyclomaticComplexityVisitor
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyRecursiveElementVisitor
import org.jetbrains.plugins.groovy.lang.psi.api.GrDoWhileStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrForStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrIfStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrSwitchStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrWhileStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrConditionalExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrSwitchExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Uses IntelliJ PSI for accurate cyclomatic complexity on Java, Kotlin, and Groovy files.
 * Returns null for unsupported file types, signalling the caller to fall back to keyword counting.
 */
object PsiComplexityCalculator {
    fun calculate(file: VirtualFile, project: Project): Int? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        return when (psiFile) {
            is PsiJavaFile -> {
                val visitor = CyclomaticComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            is KtFile -> {
                val visitor = KotlinComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            is GroovyFile -> {
                val visitor = GroovyComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            else -> null
        }
    }
}

private class KotlinComplexityVisitor : KtTreeVisitorVoid() {
    var complexity = 0
        private set

    override fun visitIfExpression(expression: KtIfExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitForExpression(expression: KtForExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitWhileExpression(expression: KtWhileExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        // Each non-else arm is a separate decision path (mirrors Java switch-case CC counting)
        val branchCount = expression.entries.count { !it.isElse }.coerceAtLeast(1)
        complexity += branchCount
        expression.acceptChildren(this)
    }

    override fun visitCatchSection(catchClause: KtCatchClause) {
        complexity++
        catchClause.acceptChildren(this)
    }
}

private class GroovyComplexityVisitor : GroovyRecursiveElementVisitor() {
    var complexity = 0
        private set

    override fun visitIfStatement(ifStatement: GrIfStatement) {
        complexity++
        super.visitIfStatement(ifStatement)
    }

    override fun visitForStatement(forStatement: GrForStatement) {
        complexity++
        super.visitForStatement(forStatement)
    }

    override fun visitWhileStatement(whileStatement: GrWhileStatement) {
        complexity++
        super.visitWhileStatement(whileStatement)
    }

    override fun visitDoWhileStatement(doWhileStatement: GrDoWhileStatement) {
        complexity++
        super.visitDoWhileStatement(doWhileStatement)
    }

    override fun visitSwitchStatement(switchStatement: GrSwitchStatement) {
        complexity += switchStatement.caseSections.count { !it.isDefault }.coerceAtLeast(1)
        super.visitSwitchStatement(switchStatement)
    }

    override fun visitSwitchExpression(switchExpression: GrSwitchExpression) {
        complexity += switchExpression.caseSections.count { !it.isDefault }.coerceAtLeast(1)
        super.visitSwitchExpression(switchExpression)
    }

    override fun visitConditionalExpression(expression: GrConditionalExpression) {
        complexity++
        super.visitConditionalExpression(expression)
    }
}
