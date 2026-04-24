package com.github.projectstats.coverage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Stores user's coverage report selections per project.
 * Allows enabling/disabling auto-discovered reports and manually adding custom report paths.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CoverageReportSettings",
    storages = [Storage("codescape.xml")]
)
class CoverageReportSettings : PersistentStateComponent<CoverageReportSettings> {

    data class ReportEntry(
        var path: String = "",
        var enabled: Boolean = true,
    )

    /** Paths of manually added coverage reports (absolute or project-relative). */
    var manualReports: MutableList<ReportEntry> = mutableListOf()

    /** Paths of auto-discovered reports that should be excluded. */
    var excludedPaths: MutableSet<String> = mutableSetOf()

    override fun getState(): CoverageReportSettings = this

    override fun loadState(state: CoverageReportSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): CoverageReportSettings =
            project.getService(CoverageReportSettings::class.java)
    }
}
