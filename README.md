# Project Stats — IntelliJ Plugin

[![CI](https://github.com/catatafishen/project-stats/actions/workflows/ci.yml/badge.svg)](https://github.com/catatafishen/project-stats/actions/workflows/ci.yml)
[![CodeQL](https://github.com/catatafishen/project-stats/actions/workflows/codeql.yml/badge.svg)](https://github.com/catatafishen/project-stats/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/catatafishen/project-stats/badge)](https://scorecard.dev/viewer/?uri=github.com/catatafishen/project-stats)
[![codecov](https://codecov.io/gh/catatafishen/project-stats/branch/master/graph/badge.svg)](https://codecov.io/gh/catatafishen/project-stats)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A JetBrains plugin that visualizes where your project's source code size comes from.

## Features

- **Tool window** "Project Stats" on the right-hand side.
- **Group by** one of:
    - Language (by IntelliJ file type: Kotlin, Java, TypeScript, …)
    - Module
    - Source category (Sources, Tests, Resources, Test Resources, Generated, Other)
    - Directory tree (drill-down via double-click)
- **Metric** selector: Total LOC, Non-blank LOC, File size, File count.
- **Filters**: include/exclude tests, resources, generated sources, or “other”.
- **Visualizations**:
    - GitHub-style stacked bar (percent per bucket).
    - Squarified treemap with stable colors, tooltips, and drill-down.
    - Sortable table with files / LOC / non-blank / size / percent / children.
- **Background scanning** with progress, cancellation, and large-file guard (≤ 4 MiB per file for LOC counting).

Uses IntelliJ's `ProjectFileIndex`, `GeneratedSourcesFilter`, and JPS `JavaSourceRootType` to get authoritative
categorization, and `VirtualFile` charsets for accurate line counts.

## Build

Requires JDK 17+.

```bash
./gradlew buildPlugin
```

The installable zip lands in `build/distributions/`.

To run an IDE sandbox with the plugin loaded:

```bash
./gradlew runIde
```

## Layout

```
src/main/kotlin/com/github/projectstats/
  Model.kt                 # FileStat, StatGroup, Metric, GroupBy, ScanResult
  ProjectScanner.kt        # Walks the project, classifies & counts lines
  StatsAggregator.kt       # Groups FileStats by the chosen dimension
  TreemapPanel.kt          # Squarified treemap (custom Swing)
  StackedBarPanel.kt       # GitHub-style percentage bar
  ProjectStatsPanel.kt     # Tool window UI: toolbar, bar, treemap, table
  ProjectStatsToolWindowFactory.kt
src/main/resources/META-INF/plugin.xml
```

## Releases & security

- **CI** (`.github/workflows/ci.yml`) — builds, tests, runs `verifyPlugin` against marketplace-recommended IDE versions,
  and uploads coverage to Codecov.
- **Release** (`.github/workflows/release.yml`) — on every push to `master`, derives the next semver bump from
  conventional-commit messages, builds the plugin ZIP with the version + generated changelog injected, signs it with *
  *cosign** (keyless), produces a SLSA-style **build provenance attestation**, and creates a GitHub release.
- **Publish to JetBrains Marketplace** (`.github/workflows/publish-marketplace.yml`) — manual `workflow_dispatch`.
  Downloads the chosen release's signed ZIP, previews the changelog, then waits for `marketplace` environment approval
  before uploading via the Marketplace API.
- **CodeQL**, **OpenSSF Scorecard**, **Zizmor** — security scanning for code, repo configuration, and workflow
  definitions.

Required secrets / settings:

- `secrets.JETBRAINS_MARKETPLACE_TOKEN` — needed by the publish workflow (set on the `marketplace` environment).
- `secrets.CODECOV_TOKEN` — optional; CI tolerates its absence.
- Environments `release` (auto-approved) and `marketplace` (require reviewers).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
