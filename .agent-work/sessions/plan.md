# Project Stats IntelliJ Plugin

## Goal
Plugin that visualizes where a project's source code "size" comes from. Show LOC distribution by language, module, source category (source/test/resources/generated), and directory tree — similar to GitHub's repo language bar, but richer.

## Approach
- Kotlin plugin, IntelliJ Platform Gradle Plugin 2.x, target 2024.2+.
- Tool window "Project Stats" on the right.
- Scanner (background task) walks `ProjectRootManager.contentSourceRoots` + project base dir, classifies each file by:
  - Module (ProjectFileIndex.getModuleForFile)
  - Language/extension (FileType)
  - Source category: SOURCE / TEST / RESOURCES / TEST_RESOURCES / GENERATED / OTHER (JavaSourceRootType, JavaResourceRootType, isInGeneratedSources, isExcluded)
  - Directory relative path
- LOC: total lines and non-blank lines (read via VFS, skip binaries & files > size cap).
- UI:
  - Top toolbar: Refresh, Group-by selector (Language | Module | Category | Directory), Metric selector (LOC | Non-blank LOC | File size | File count), include tests/generated toggles.
  - Middle: squarified treemap (custom JPanel) with colored rectangles, tooltips, double-click to drill down (dirs).
  - Right/bottom: GitHub-style stacked bar + sortable table with totals and percentages.

## Todos
- scaffold gradle + plugin.xml
- implement scanner + model
- implement treemap component
- implement bar + table + toolbar
- wire tool window factory + actions
- build and verify
