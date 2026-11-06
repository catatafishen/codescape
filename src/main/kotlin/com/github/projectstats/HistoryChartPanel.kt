package com.github.projectstats

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Scrollable
import kotlin.math.max
import kotlin.math.min

/** How commits are spaced along the X axis. */
enum class HistoryXScale(val display: String) {
    INDEX("Even spacing"),
    TIME("By date"),
    ;

    override fun toString() = display
}

/**
 * Hand-painted stacked-area chart of cumulative project size over mainline commit history,
 * with drill-down into directories (mirrors [TreemapPanel]'s drill API so [ProjectStatsPanel]
 * can drive either component through the same breadcrumb row).
 *
 * Single-click on a band drills into it, collapsing through any chain of directories that have
 * only one child (same convention as [TreemapPanel]). Right-click pops one level. Hovering a
 * commit node shows a detailed per-commit tooltip; clicking it opens the commit in the IDE's Git
 * log tool window via [onOpenCommit]. Hovering elsewhere on a band shows a simpler
 * name/value-at-nearest-commit tooltip.
 */
class HistoryChartPanel : JPanel(BorderLayout()) {

    private data class HistoryDrillStep(val chain: List<String>) {
        fun display(): String = chain.joinToString("/")
    }

    private var data: HistoryData = HistoryData(emptyList())
    private var series: HistorySeriesSet = HistorySeriesSet(emptyList(), LongArray(0), emptyList())
    private val drillStack = ArrayDeque<HistoryDrillStep>()
    private var onDrillChanged: ((List<String>) -> Unit)? = null
    private var onOpenCommit: ((HistoryCommit) -> Unit)? = null
    private var xScale: HistoryXScale = HistoryXScale.INDEX

    private val canvas = ChartCanvas()
    private var zoom = 1.0
    private val canvasScroll = JBScrollPane(canvas).apply {
        border = BorderFactory.createEmptyBorder()
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
    }
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
        add(canvasScroll, BorderLayout.CENTER)
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

    /** Invoked when the user clicks a commit node marker. */
    fun setOnOpenCommit(listener: (HistoryCommit) -> Unit) {
        onOpenCommit = listener
    }

    fun setXScale(scale: HistoryXScale) {
        if (xScale == scale) return
        xScale = scale
        canvas.repaint()
    }

    private fun flatPath(): List<String> = drillStack.flatMap { it.chain }

    fun drillDepth(): Int = drillStack.size

    fun drillStepDisplay(index: Int): String = drillStack.elementAt(index).display()

    /** Mirrors [TreemapPanel.drillPathKeys] shape so callers can treat both components uniformly. */
    fun drillPathKeys(): List<List<String>> = drillStack.map { it.chain }

    fun popToDepth(keep: Int) {
        if (keep < 0 || keep >= drillStack.size) return
        while (drillStack.size > keep) drillStack.removeLast()
        recompute()
        onDrillChanged?.invoke(flatPath())
    }

    fun popDrill(): Boolean {
        if (drillStack.isEmpty()) return false
        drillStack.removeLast()
        recompute()
        onDrillChanged?.invoke(flatPath())
        return true
    }

    /** Drills into [name], collapsing through any chain of single-child directories (mirrors [TreemapPanel]). */
    private fun drillInto(name: String) {
        val chain = ArrayList<String>()
        chain.add(name)
        var currentPath = flatPath() + name
        var currentSeries = HistoryAggregator.seriesFor(data, currentPath)
        while (currentSeries.children.size == 1 && currentSeries.children[0].hasChildren) {
            val childName = currentSeries.children[0].name
            chain.add(childName)
            currentPath = currentPath + childName
            currentSeries = HistoryAggregator.seriesFor(data, currentPath)
        }
        drillStack.addLast(HistoryDrillStep(chain))
        recompute()
        onDrillChanged?.invoke(flatPath())
    }

    private fun recompute() {
        series = HistoryAggregator.seriesFor(data, flatPath())
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
                toolTipText = if (child.hasChildren) "Click to drill into ${child.name}" else child.name
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

    /** Zooms the X axis by [factor], keeping the content under [mouseX] (canvas coords) stationary. */
    private fun zoomBy(factor: Double, mouseX: Int) {
        val viewport = canvasScroll.viewport
        val oldW = canvas.width.coerceAtLeast(1)
        val newZoom = (zoom * factor).coerceIn(1.0, 200.0)
        if (newZoom == zoom) return
        zoom = newZoom
        val newW = (viewport.width * zoom).toInt().coerceAtLeast(viewport.width)
        val offsetInView = mouseX - viewport.viewPosition.x
        val newMouseX = (mouseX.toDouble() / oldW * newW).toInt()
        canvas.preferredSize = Dimension(newW, viewport.height)
        canvas.setSize(newW, viewport.height)
        canvas.revalidate()
        viewport.viewPosition =
            Point((newMouseX - offsetInView).coerceIn(0, (newW - viewport.width).coerceAtLeast(0)), 0)
        canvas.repaint()
    }

    private inner class ChartCanvas : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 20
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = max(20, r.width - 40)
        override fun getScrollableTracksViewportWidth() = zoom <= 1.0
        override fun getScrollableTracksViewportHeight() = true

        private var bands: List<Triple<Path2D.Double, ChildSeries, Int>> = emptyList()
        private var nodeX: DoubleArray = DoubleArray(0)
        private var nodeY: DoubleArray = DoubleArray(0)
        private val marginLeft = 60
        private val marginRight = 12
        private val marginTop = 10
        private val marginBottom = 10
        private val nodeHitRadius = 6.0
        private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        init {
            background = JBColor.background()
            toolTipText = ""
            addMouseWheelListener { e ->
                val factor = Math.pow(1.25, -e.preciseWheelRotation)
                zoomBy(factor, e.x)
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                        val nodeIdx = hitNode(e.x, e.y)
                        if (nodeIdx != null) {
                            onOpenCommit?.invoke(series.commits[nodeIdx])
                            return
                        }
                        hitBand(e.x, e.y)?.let { if (it.hasChildren) drillInto(it.name) }
                    } else if (e.button == MouseEvent.BUTTON3) {
                        popDrill()
                    }
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (hitNode(e.x, e.y) != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                }
            })
        }

        private fun hitBand(x: Int, y: Int): ChildSeries? =
            bands.lastOrNull { (shape, _, _) -> shape.contains(x.toDouble(), y.toDouble()) }?.second

        /** Index of the commit node under (or within [nodeHitRadius] of) the given point, if any. */
        private fun hitNode(x: Int, y: Int): Int? {
            var best: Int? = null
            var bestDist = nodeHitRadius
            for (i in nodeX.indices) {
                val dx = nodeX[i] - x
                val dy = nodeY[i] - y
                val dist = Math.hypot(dx, dy)
                if (dist <= bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            return best
        }

        private fun nearestIndex(mouseX: Int): Int {
            val n = nodeX.size
            if (n == 0) return -1
            var best = 0
            var bestDist = Double.MAX_VALUE
            for (i in 0 until n) {
                val dist = Math.abs(nodeX[i] - mouseX)
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            return best
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val n = series.commits.size
            if (n == 0) return null
            val nodeIdx = hitNode(event.x, event.y)
            if (nodeIdx != null) return commitTooltip(nodeIdx)

            val i = nearestIndex(event.x)
            if (i < 0) return null
            val band = hitBand(event.x, event.y)
            return if (band != null) {
                "<html><b>${band.name}</b><br>${compactCount(band.values[i])}</html>"
            } else {
                "<html>Total: ${compactCount(series.total[i])}</html>"
            }
        }

        private fun commitTooltip(i: Int): String {
            val commit = series.commits[i]
            val date = Instant.ofEpochSecond(commit.epochSeconds).atZone(ZoneId.systemDefault()).format(dateFmt)
            val sb = StringBuilder("<html><b>$date</b> (${commit.sha.take(7)})<br>")
            sb.append("Total: ${compactCount(series.total[i])}<br>")
            for (child in series.children.take(8)) {
                sb.append("${child.name}: ${compactCount(child.values[i])}<br>")
            }
            if (series.children.size > 8) sb.append("&hellip; ${series.children.size - 8} more")
            sb.append("<br><i>Click to open in Git log</i>")
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
                nodeX = DoubleArray(0)
                nodeY = DoubleArray(0)
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

            val minT = series.commits.first().epochSeconds
            val maxT = series.commits.last().epochSeconds
            val tRange = (maxT - minT).coerceAtLeast(1L)

            fun xAt(i: Int): Double = when (xScale) {
                HistoryXScale.INDEX -> plotLeft + plotW * (if (n <= 1) 0.0 else i.toDouble() / (n - 1))
                HistoryXScale.TIME -> plotLeft + plotW * (series.commits[i].epochSeconds - minT).toDouble() / tRange
            }

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
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)
            for (i in 0 until n) {
                val x = xAt(i)
                val y = yAt(series.total[i])
                xs[i] = x
                ys[i] = y
                if (i == 0) totalPath.moveTo(x, y) else totalPath.lineTo(x, y)
            }
            g2.draw(totalPath)
            g2.stroke = BasicStroke(1f)
            nodeX = xs
            nodeY = ys

            // Commit node markers on the total line.
            g2.color = JBColor.foreground()
            for (i in 0 until n) {
                val marker = Ellipse2D.Double(xs[i] - 2.5, ys[i] - 2.5, 5.0, 5.0)
                g2.fill(marker)
            }

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
