package com.github.projectstats

import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.siyeh.ig.classmetrics.CyclomaticComplexityVisitor

/**
 * Uses IntelliJ PSI for cyclomatic complexity on any language JetBrains provides a PSI tree for.
 *
 *  - Java: precise visitor (counts if/for/while/switch/catch/ternary/&&/|| — includes short-circuit operators)
 *  - Everything else with a PSI tree (Kotlin, Groovy, Python, JS/TS, PHP, Go, Ruby, Rust, Scala, Dart…):
 *    heuristic visitor that recognizes branching nodes by PSI class simple name.
 *    Operators (&&/||) are NOT counted for these languages since that would require language-specific
 *    AST knowledge, but all control-flow structures (if/for/while/switch cases/catch/ternary) are.
 *
 *  Returns null only for files with no PSI tree or plain-text/binary content, signalling the caller
 *  to fall back to keyword counting.
 */
object PsiComplexityCalculator {
    fun calculate(file: VirtualFile, project: Project): Int? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        // Compiled .class files must not be walked with a recursive visitor — too slow and logs errors.
        if (psiFile is ClsFileImpl) return null
        // Plain text / unknown files have no real PSI tree — let the keyword fallback handle them.
        if (psiFile.language == PlainTextLanguage.INSTANCE) return null
        return when (psiFile) {
            is PsiJavaFile -> {
                val visitor = CyclomaticComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            else -> {
                val visitor = GenericPsiComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
        }
    }
}

/**
 * Recognizes decision-point PSI nodes across every JetBrains language by their class simple name.
 *
 * Matched classes per language family:
 *  - Kotlin:   KtIfExpression, KtForExpression, KtWhileExpression, KtDoWhileExpression,
 *              KtWhenEntry, KtCatchClause
 *  - Groovy:   GrIfStatement, GrForStatement, GrWhileStatement, GrDoWhileStatement,
 *              GrCaseSection, GrConditionalExpression
 *  - Python:   PyIfStatement, PyForStatement, PyWhileStatement, PyConditionalExpression, PyExceptPart
 *  - JS/TS:    JSIfStatement, JSForStatement, JSForInStatement, JSForOfStatement,
 *              JSWhileStatement, JSDoWhileStatement, JSCaseClause, JSConditionalExpression, JSCatchBlock
 *  - PHP:      PhpIfStatement, PhpForStatement, PhpForeachStatement, PhpWhileStatement,
 *              PhpDoWhileStatement, PhpCaseList, PhpCatch (TernaryExpression also matched)
 *  - Go:       GoIfStatement, GoForStatement, GoCaseClause, GoTypeCaseClause
 *  - Ruby:     RIfStatement, RForStatement, RWhileStatement, RUntilStatement,
 *              RWhenEntry, RRescueClause, RTernaryExpression
 *  - Rust:     RsIfExpr, RsForExpr, RsWhileExpr, RsLoopExpr, RsMatchArm
 *  - Scala:    ScIfStmt, ScForStatement, ScWhileStmt, ScDoStmt, ScCaseClause
 *  - Dart:     DartIfStatement, DartForStatement, DartWhileStatement, DartDoWhileStatement,
 *              DartSwitchCase, DartCatchPart, DartTernaryExpression
 *  - Terraform/HCL: HCLConditionalExpression
 *  - C#/CFML/etc. via plugins: equivalent Xxx*IfStatement / Xxx*CaseClause classes
 *
 * The switch/match container itself is NOT counted — each case/arm/entry is a separate branch.
 */
private class GenericPsiComplexityVisitor : PsiRecursiveElementVisitor() {
    var complexity = 0
        private set

    override fun visitElement(element: PsiElement) {
        if (isBranchingNode(element::class.java.simpleName)) complexity++
        super.visitElement(element)
    }

    private fun isBranchingNode(className: String): Boolean {
        val n = className.lowercase()
        return when {
            // if / conditional / ternary
            n.endsWith("ifstatement") || n.endsWith("ifexpression")
                    || n.endsWith("ifexpr") || n.endsWith("ifstmt") -> true
            n.endsWith("conditionalexpression") || n.endsWith("ternaryexpression") -> true

            // for / foreach
            n.endsWith("forstatement") || n.endsWith("forexpression")
                    || n.endsWith("forexpr") || n.endsWith("forstmt")
                    || n.endsWith("foreachstatement") || n.endsWith("forinstatement")
                    || n.endsWith("forofstatement") -> true

            // while / do-while / until / loop
            n.endsWith("whilestatement") || n.endsWith("whileexpression")
                    || n.endsWith("whileexpr") || n.endsWith("whilestmt")
                    || n.endsWith("dowhilestatement") || n.endsWith("dowhileexpression")
                    || n.endsWith("dowhilestmt") || n.endsWith("dostmt")
                    || n.endsWith("untilstatement") || n.endsWith("loopexpr") -> true

            // catch / except / rescue
            n.endsWith("catchclause") || n.endsWith("catchsection")
                    || n.endsWith("catchblock") || n.endsWith("catchpart")
                    || n.endsWith("exceptpart") || n.endsWith("exceptclause")
                    || n.endsWith("rescueclause") || n.endsWith("rescuestatement") -> true

            // case / when entries / match arms (each branch is a decision point)
            n.endsWith("caseclause") || n.endsWith("casesection")
                    || n.endsWith("casepart") || n.endsWith("caseitem")
                    || n.endsWith("caselist") || n.endsWith("caselabel")
                    || n.endsWith("typecaseclause") -> true
            n.endsWith("matcharm") || n.endsWith("matchcase") -> true
            n.endsWith("whenentry") -> true

            else -> false
        }
    }
}
