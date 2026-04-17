package com.github.projectstats

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ProjectScanner {

    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 // skip files larger than 4 MiB

    fun scan(project: Project, indicator: ProgressIndicator?): ScanResult {
        val started = System.currentTimeMillis()

        indicator?.text = "Reading git history"
        val commitCounts: Map<String, Int> = GitCommitCountCalculator.compute(project, indicator)

        indicator?.text = "Scanning project files"

        val rootManager = ProjectRootManager.getInstance(project)
        val fileIndex = rootManager.fileIndex
        val projectBase: VirtualFile? = project.baseDir

        val roots = LinkedHashSet<VirtualFile>()
        roots.addAll(rootManager.contentRoots)
        if (projectBase != null) roots.add(projectBase)

        // Phase 1: walk VFS (fast, sequential) to collect files.
        val toProcess = ArrayList<VirtualFile>(4096)
        val seenPaths = HashSet<String>(4096)
        for (root in roots) {
            indicator?.checkCanceled()
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any?>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    indicator?.checkCanceled()
                    if (file.isDirectory) {
                        val name = file.name
                        // Skip common build output / VCS dirs early for speed
                        if (name == ".git" || name == ".hg" || name == ".idea" ||
                            name == "node_modules" || name == "build" || name == "out" ||
                            name == "target" || name == ".gradle" || name == "dist"
                        ) return false
                        return true
                    }
                    if (seenPaths.add(file.path)) toProcess += file
                    return true
                }
            })
        }

        // Phase 2: classify in parallel. ReadAction is a shared read lock — multiple workers OK.
        val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val executor = Executors.newFixedThreadPool(parallelism) { r ->
            Thread(r, "project-stats-scanner").apply { isDaemon = true }
        }

        val files = ArrayList<FileStat>(toProcess.size)
        var totalLines = 0L
        var nonBlank = 0L
        var codeL = 0L
        var complexityTotal = 0L
        var size = 0L
        var commitsTotal = 0L
        val progressStep = (toProcess.size / 100).coerceAtLeast(50)

        try {
            val futures = toProcess.map { file ->
                executor.submit(Callable<FileStat?> {
                    ReadAction.compute<FileStat?, RuntimeException> {
                        classify(file, project, fileIndex, projectBase)
                    }?.let { base ->
                        if (commitCounts.isEmpty()) base
                        else base.copy(commitCount = commitCounts[file.path] ?: 0)
                    }
                })
            }
            var done = 0
            for ((idx, f) in futures.withIndex()) {
                indicator?.checkCanceled()
                val stat = try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    val cause = e.cause
                    if (cause is RuntimeException) throw cause
                    throw RuntimeException(cause ?: e)
                }
                done++
                if (done % progressStep == 0) {
                    indicator?.text2 = "Scanning ${toProcess[idx].presentableUrl}"
                }
                if (stat == null) continue
                files += stat
                totalLines += stat.totalLines
                nonBlank += stat.nonBlankLines
                codeL += stat.codeLines
                complexityTotal += stat.complexity
                size += stat.sizeBytes
                commitsTotal += stat.commitCount
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        return ScanResult(
            files = files,
            totalLines = totalLines,
            nonBlankLines = nonBlank,
            codeLines = codeL,
            complexity = complexityTotal,
            sizeBytes = size,
            fileCount = files.size.toLong(),
            commitCount = commitsTotal,
            scannedMillis = System.currentTimeMillis() - started,
        )
    }

    private fun classify(
        file: VirtualFile,
        project: Project,
        fileIndex: ProjectFileIndex,
        projectBase: VirtualFile?,
    ): FileStat? {
        if (!file.isValid || file.isDirectory) return null
        if (fileIndex.isExcluded(file)) return null
        // Ignore files that are in libraries (JARs, SDK sources)
        if (fileIndex.isInLibrary(file)) return null

        val size = file.length
        if (size <= 0) return null
        if (size > MAX_FILE_BYTES) {
            // still count its size/file but skip LOC
            return FileStat(
                relativePath = relPath(file, projectBase),
                language = languageName(file.fileType),
                extension = file.extension?.lowercase() ?: "",
                module = moduleName(file, fileIndex),
                category = categorize(file, fileIndex),
                totalLines = 0,
                nonBlankLines = 0,
                codeLines = 0,
                complexity = 0,
                sizeBytes = size,
            )
        }

        val fileType = file.fileType
        val isBinary = fileType.isBinary
        var total = 0
        var nonBlank = 0
        var codeL = 0
        var complexity = 0
        if (!isBinary) {
            try {
                val bytes = file.contentsToByteArray(false)
                val charset = file.charset
                val text = String(bytes, charset)
                val ext = file.extension?.lowercase() ?: ""
                val stats = LineCounter.count(text, ext)
                total = stats.total
                nonBlank = stats.nonBlank
                codeL = stats.code
                complexity = stats.complexity
            } catch (_: Throwable) {
                // ignore unreadable files; keep size only
            }
        }

        // PSI-based complexity is more accurate for Java/Kotlin (handles operators, no false positives
        // from string literals). Falls back to the keyword count already computed above for other languages.
        // Restrict to extensions we actually treat as code to avoid parsing JSON/XML/YAML/MD trees that
        // yield no branches but still cost CPU. Binary files are also skipped.
        if (!isBinary) {
            val ext = file.extension?.lowercase() ?: ""
            if (LineCounter.DECISION_KEYWORDS.containsKey(ext)) {
                val psiComplexity = PsiComplexityCalculator.calculate(file, project)
                if (psiComplexity != null) complexity = psiComplexity
            }
        }

        return FileStat(
            relativePath = relPath(file, projectBase),
            language = languageName(fileType),
            extension = file.extension?.lowercase() ?: "",
            module = moduleName(file, fileIndex),
            category = categorize(file, fileIndex),
            totalLines = total,
            nonBlankLines = nonBlank,
            codeLines = codeL,
            complexity = complexity,
            sizeBytes = size,
        )
    }

    private fun relPath(file: VirtualFile, base: VirtualFile?): String {
        if (base == null) return file.path
        val rel = VfsUtilCore.getRelativePath(file, base, '/')
        return rel ?: file.path
    }

    private fun languageName(fileType: FileType): String {
        val n = fileType.name
        return when {
            n.equals("PLAIN_TEXT", true) -> "Text"
            n.isBlank() -> "Other"
            else -> n
        }
    }

    private fun moduleName(file: VirtualFile, idx: ProjectFileIndex): String {
        return idx.getModuleForFile(file)?.name ?: "<no module>"
    }

    private fun categorize(file: VirtualFile, idx: ProjectFileIndex): SourceCategory {
        // Generated sources: ask the platform extension point. Use the project from module if available.
        val module = idx.getModuleForFile(file)
        if (module != null) {
            if (GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(
                    file,
                    module.project
                )
            ) return SourceCategory.GENERATED
        }
        val rootType: JpsModuleSourceRootType<*>? = idx.getContainingSourceRootType(file)
        return when (rootType) {
            JavaSourceRootType.SOURCE -> SourceCategory.SOURCE
            JavaSourceRootType.TEST_SOURCE -> SourceCategory.TEST
            JavaResourceRootType.RESOURCE -> SourceCategory.RESOURCES
            JavaResourceRootType.TEST_RESOURCE -> SourceCategory.TEST_RESOURCES
            null -> {
                // Not under a configured source root: heuristic by path
                val path = file.path.lowercase()
                when {
                    "/test/" in path || path.endsWith("/test") || "/tests/" in path -> SourceCategory.TEST
                    "/generated/" in path || "/gen/" in path || "/build/" in path || "/out/" in path -> SourceCategory.GENERATED
                    "/resources/" in path -> SourceCategory.RESOURCES
                    else -> SourceCategory.OTHER
                }
            }

            else -> SourceCategory.SOURCE
        }
    }
}

