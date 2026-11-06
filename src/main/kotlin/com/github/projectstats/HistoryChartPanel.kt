package com.github.projectstats

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min

/**
 * Hand-painted stacked-area chart of cumulative project size over mainline commit history,
 * with drill-down into directories (mirrors [TreemapPanel]'s drill API so [ProjectStatsPanel]
 * can drive either component through the same breadcrumb row).
 */
class HistoryChartPanel : JPanel(BorderLayout()) {

    private var data: HistoryData = HistoryData(emptyList())
    private var series: HistorySeriesSet = HistorySeriesSet(emptyList(), LongArray(0), emptyList())
    private val path = ArrayList<String>()
    private var onDrillChanged: ((List<String>) -> Unit)? = null

    private val canvas = ChartCanvas()
    private val legend = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4)
    }
    private val legendScroll = JBScrollPane(legend).apply {
        border = BorderFactory.createEmptyBorder()
        preferredSize = Dimension(180, 0)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        add(canvas, BorderLayout.CENTER)
        add(legendScroll, BorderLayout.EAST)
    }

    /** Replace the full dataset (called on every streamed batch); preserves the current drill path. */
    fun setData(newData: HistoryData) {
        data = newData
        recompute()
    }

    fun setOnDrillChanged(listener: (List<String>) -> Unit) {
        onDrillChanged = listener
    }

    fun drillDepth(): Int = path.size

    fun drillStepDisplay(index: Int): String = path[index]

    /** Mirrors [TreemapPanel.drillPathKeys] shape so callers can treat both components uniformly. */
    fun drillPathKeys(): List<List<String>> = path.map { listOf(it) }

    fun currentPath(): List<String> = path.toList()

    fun popToDepth(keep: Int) {
        if (keep < 0 || keep >= path.size) return
        while (path.size > keep) path.removeAt(path.size - 1)
        recompute()
        onDrillChanged?.invoke(path.toList())
    }

    fun popDrill(): Boolean {
        if (path.isEmpty()) return false
        path.removeAt(path.size - 1)
        recompute()
        onDrillChanged?.invoke(path.toList())
        return true
    }

    private fun drillInto(name: String) {
        path.add(name)
        recompute()
        onDrillChanged?.invoke(path.toList())
    }

    private fun recompute() {
        series = HistoryAggregator.seriesFor(data, path)
        rebuildLegend()
        canvas.repaint()
    }

    private fun rebuildLegend() {
        legend.removeAll()
        for (child in series.children) {
            val swatch = JPanel().apply {
                background = hashColor(child.name)
                preferredSize = Dimension(10, 10)
                maximumSize = Dimension(10, 10)
            }
            val label = JBLabel("${child.name}: ${compactCount(child.values.lastOrNull() ?: 0L)}").apply {
                font = font.deriveFont(11f)
            }
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
                add(swatch)
                add(label)
                toolTipText = if (child.hasChildren) "Double-click to drill into ${child.name}" else child.name
                if (child.hasChildren) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (child.hasChildren) drillInto(child.name)
                    }
                })
            }
            legend.add(row)
        }
        legend.revalidate()
        legend.repaint()
    }

    private inner class ChartCanvas : JPanel() {
        private var bands: List<Triple<Path2D.Double, ChildSeries, Int>> = emptyList()
        private val marginLeft = 60
        private val marginRight = 12
        private val marginTop = 10
        private val marginBottom = 10
        private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        init {
            background = JBColor.background()
            toolTipText = ""
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                        hitBand(e.x, e.y)?.let { if (it.hasChildren) drillInto(it.name) }
                    } else if (e.button == MouseEvent.BUTTON3) {
                        popDrill()
                    }
                }
            })
        }

        private fun hitBand(x: Int, y: Int): ChildSeries? =
            bands.lastOrNull { (shape, _, _) -> shape.contains(x.toDouble(), y.toDouble()) }?.second

        private fun nearestIndex(mouseX: Int): Int {
            val n = series.commits.size
            if (n == 0) return -1
            val plotW = (width - marginLeft - marginRight).coerceAtLeast(1)
            val frac = ((mouseX - marginLeft).toDouble() / plotW).coerceIn(0.0, 1.0)
            return (frac * (n - 1)).toInt().coerceIn(0, n - 1)
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val n = series.commits.size
            if (n == 0) return null
            val i = nearestIndex(event.x)
            val commit = series.commits[i]
            val date = Instant.ofEpochSecond(commit.epochSeconds).atZone(ZoneId.systemDefault()).format(dateFmt)
            val sb = StringBuilder("<html><b>$date</b> (${commit.sha.take(7)})<br>")
            sb.append("Total: ${compactCount(series.total[i])}<br>")
            for (child in series.children.take(8)) {
                sb.append("${child.name}: ${compactCount(child.values[i])}<br>")
            }
            if (series.children.size > 8) sb.append("&hellip; ${series.children.size - 8} more")
            sb.append("</html>")
            return sb.toString()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val n = series.commits.size
            if (n == 0) {
                bands = emptyList()
                g2.color = JBColor.GRAY
                g2.drawString("No history scanned yet", marginLeft, height / 2)
                return
            }

            val plotLeft = marginLeft
            val plotRight = width - marginRight
            val plotTop = marginTop
            val plotBottom = height - marginBottom
            val plotW = (plotRight - plotLeft).coerceAtLeast(1)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1)

            val maxVal = max(1L, series.total.maxOrNull() ?: 0L)
            val minVal = min(0L, series.total.minOrNull() ?: 0L)
            val range = (maxVal - minVal).coerceAtLeast(1L)

            fun xAt(i: Int): Double = plotLeft + plotW * (if (n <= 1) 0.0 else i.toDouble() / (n - 1))
            fun yAt(v: Long): Double = plotBottom - plotH * ((v - minVal).toDouble() / range)

            // Gridlines + Y-axis labels at 0%, 50%, 100% of the value range.
            g2.color = JBColor.border()
            g2.font = g2.font.deriveFont(10f)
            for (frac in listOf(0.0, 0.5, 1.0)) {
                val v = minVal + (range * frac).toLong()
                val y = yAt(v)
                g2.draw(Line2D.Double(plotLeft.toDouble(), y, plotRight.toDouble(), y))
                g2.color = JBColor.GRAY
                g2.drawString(compactCount(v), 2, (y + 4).toInt())
                g2.color = JBColor.border()
            }

            // Stacked bands, bottom-up; built once per paint since drill/data changes already
            // trigger repaint via recompute().
            val runningBase = LongArray(n)
            val placed = ArrayList<Triple<Path2D.Double, ChildSeries, Int>>()
            for ((idx, child) in series.children.withIndex()) {
                val path = Path2D.Double()
                for (i in 0 until n) {
                    val x = xAt(i)
                    val y = yAt(runningBase[i] + child.values[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (i in n - 1 downTo 0) {
                    val x = xAt(i)
                    val y = yAt(runningBase[i])
                    path.lineTo(x, y)
                }
                path.closePath()
                g2.color = hashColor(child.name)
                g2.fill(path)
                placed += Triple(path, child, idx)
                for (i in 0 until n) runningBase[i] += child.values[i]
            }
            bands = placed

            // Bold total line along the stack top (== sum of all children by construction).
            g2.color = JBColor.foreground()
            g2.stroke = BasicStroke(1.8f)
            val totalPath = Path2D.Double()
            for (i in 0 until n) {
                val x = xAt(i)
                val y = yAt(series.total[i])
                if (i == 0) totalPath.moveTo(x, y) else totalPath.lineTo(x, y)
            }
            g2.draw(totalPath)
            g2.stroke = BasicStroke(1f)

            // Date range footer.
            g2.color = JBColor.GRAY
            val first = series.commits.first()
            val last = series.commits.last()
            val firstDate = Instant.ofEpochSecond(first.epochSeconds).atZone(ZoneId.systemDefault()).format(dateFmt)
            val lastDate = Instant.ofEpochSecond(last.epochSeconds).atZone(ZoneId.systemDefault()).format(dateFmt)
            g2.drawString("$firstDate \u2192 $lastDate  (${n} commits)", plotLeft, height - 1)
        }
    }
}
