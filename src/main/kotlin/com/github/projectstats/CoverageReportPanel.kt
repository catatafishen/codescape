package com.github.projectstats

import com.github.projectstats.coverage.CoverageLoader
import com.github.projectstats.coverage.CoverageReportSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*

/**
 * UI for managing coverage report selection — allows enabling/disabling auto-discovered
 * reports and manually adding/removing custom reports.
 */
class CoverageReportPanel(private val project: Project, private val onChanged: () -> Unit) : JPanel(BorderLayout()) {

    private val settings = CoverageReportSettings.getInstance(project)
    private val checkboxMap = mutableMapOf<String, JBCheckBox>()
    private val reportListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(8)

        // Header
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("Coverage Reports", Font.BOLD).apply {
                font = font.deriveFont(Font.BOLD, 12f)
            })
        }

        // Report list
        val scrollPane = JBScrollPane(reportListPanel).apply {
            preferredSize = Dimension(400, 150)
        }

        // Add button
        val addButton = JButton("Add Report", AllIcons.General.Add).apply {
            addActionListener { addReportFile() }
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(addButton)
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        refreshReportList()
    }

    private fun refreshReportList() {
        reportListPanel.removeAll()
        checkboxMap.clear()

        val projectBase = project.guessProjectDir() ?: return

        // Discover auto-detected reports
        val discovery = CoverageLoader.discoverReports(projectBase, null)
        val autoDetected = discovery.autoDetected.toMutableSet()

        // Add auto-detected reports
        if (autoDetected.isNotEmpty()) {
            reportListPanel.add(JBLabel("Auto-detected:").apply {
                font = font.deriveFont(Font.ITALIC, 10f)
                foreground = JBColor.GRAY
            })

            for (path in autoDetected) {
                val isEnabled = path !in settings.excludedPaths && path !in settings.manualReports.map { it.path }
                val checkbox = JBCheckBox(File(path).name, isEnabled).apply {
                    toolTipText = path
                    addActionListener {
                        if (isSelected) {
                            settings.excludedPaths.remove(path)
                        } else {
                            settings.excludedPaths.add(path)
                        }
                        onChanged()
                    }
                }
                checkboxMap[path] = checkbox
                reportListPanel.add(checkbox)
            }
        }

        // Add manual reports
        if (settings.manualReports.isNotEmpty()) {
            if (autoDetected.isNotEmpty()) {
                reportListPanel.add(Box.createVerticalStrut(8))
            }
            reportListPanel.add(JBLabel("Manual:").apply {
                font = font.deriveFont(Font.ITALIC, 10f)
                foreground = JBColor.GRAY
            })

            for (entry in settings.manualReports) {
                val checkbox = JBCheckBox(File(entry.path).name, entry.enabled).apply {
                    toolTipText = entry.path
                    addActionListener {
                        entry.enabled = isSelected
                        onChanged()
                    }
                }
                checkboxMap[entry.path] = checkbox

                val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    add(checkbox)
                    add(JButton(AllIcons.General.Remove).apply {
                        isFocusable = false
                        isContentAreaFilled = false
                        isBorderPainted = false
                        preferredSize = Dimension(20, 20)
                        addActionListener {
                            settings.manualReports.remove(entry)
                            refreshReportList()
                            onChanged()
                        }
                    })
                }
                reportListPanel.add(panel)
            }
        }

        reportListPanel.add(Box.createVerticalGlue())
        reportListPanel.revalidate()
        reportListPanel.repaint()
    }

    private fun addReportFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.name.endsWith(".xml") || it.name.endsWith(".info") }
            .withTitle("Select Coverage Report")
            .withDescription("Choose a coverage report file (XML or LCOV format)")

        val selected = FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) ?: return

        val projectBase = project.guessProjectDir() ?: return
        val projectPath = projectBase.path
        val selectedPath = selected.path

        // Try to make it project-relative
        val reportPath = if (selectedPath.startsWith(projectPath)) {
            selectedPath.substring(projectPath.length).removePrefix("/")
        } else {
            selectedPath
        }

        // Check if already exists
        if (settings.manualReports.none { it.path == reportPath }) {
            settings.manualReports.add(CoverageReportSettings.ReportEntry(reportPath, true))
        }

        refreshReportList()
        onChanged()
    }
}
