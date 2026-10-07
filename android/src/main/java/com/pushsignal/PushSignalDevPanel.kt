package com.pushsignal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native debugging overlay for the notification module. Rendered with plain
 * platform views (no Material/RecyclerView dependency) and attached as the last
 * child of the Activity's content root, so it floats above the React Native
 * hierarchy without intercepting touches outside its own bounds.
 *
 * Collapsed it is a small pill; tapping it expands a scrollable event log.
 */
internal object PushSignalDevPanel {
  private val COLOR_SURFACE = Color.parseColor("#F21B1B1D")
  private val COLOR_TEXT = Color.parseColor("#FFE5E7EB")
  private val COLOR_MUTED = Color.parseColor("#FF9CA3AF")
  private val COLOR_ACCENT = Color.parseColor("#FF60A5FA")
  private val COLOR_SUCCESS = Color.parseColor("#FF22C55E")
  private val COLOR_WARN = Color.parseColor("#FFF59E0B")
  private val COLOR_ERROR = Color.parseColor("#FFEF4444")

  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  private var enabled = false
  private var host: Activity? = null

  private var root: FrameLayout? = null
  private var pill: LinearLayout? = null
  private var pillDot: View? = null
  private var pillLabel: TextView? = null
  private var card: LinearLayout? = null
  private var counter: TextView? = null
  private var scroll: ScrollView? = null
  private var body: LinearLayout? = null

  private var expanded = false
  private var bodyDirty = false
  private var maxBodyHeight = 0

  private val logListener: () -> Unit = { render() }

  fun setEnabled(value: Boolean) {
    if (enabled == value) {
      return
    }
    enabled = value
    if (!value) {
      detach()
    }
  }

  fun isEnabled(): Boolean = enabled

  fun attach(activity: Activity) {
    if (!enabled) {
      return
    }
    if (host === activity && root?.parent != null) {
      return
    }
    detach()

    val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    val view = build(activity)
    content.addView(view)

    root = view
    host = activity
    PushSignalDevLog.addListener(logListener)
    render()
  }

  fun detach() {
    PushSignalDevLog.removeListener(logListener)
    root?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
    root = null
    host = null
    pill = null
    pillDot = null
    pillLabel = null
    card = null
    counter = null
    scroll = null
    body = null
    expanded = false
    bodyDirty = false
  }

  fun detachIfHost(activity: Activity) {
    if (host === activity) {
      detach()
    }
  }

  private fun build(activity: Activity): FrameLayout {
    maxBodyHeight = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()

    val rootView = FrameLayout(activity)
    rootView.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
    rootView.isClickable = false

    val column = LinearLayout(activity)
    column.orientation = LinearLayout.VERTICAL
    column.gravity = Gravity.CENTER_HORIZONTAL
    val columnParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
    columnParams.bottomMargin = dp(activity, 12)
    column.layoutParams = columnParams
    rootView.addView(column)

    ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      columnParams.bottomMargin = bars.bottom + dp(activity, 12)
      insets
    }

    column.addView(buildPill(activity))
    column.addView(buildCard(activity))
    return rootView
  }

  private fun buildPill(activity: Activity): LinearLayout {
    val row = LinearLayout(activity)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
    row.background = rounded(activity, COLOR_SURFACE, 999f)
    row.isClickable = true
    row.setOnClickListener { setExpanded(true) }
    row.elevation = dp(activity, 6).toFloat()

    val dot = View(activity)
    dot.background = oval(COLOR_ACCENT)
    val dotParams = LinearLayout.LayoutParams(dp(activity, 10), dp(activity, 10))
    dotParams.marginEnd = dp(activity, 8)
    row.addView(dot, dotParams)

    val label = TextView(activity)
    label.text = "PushSignal · 0"
    label.setTextColor(COLOR_TEXT)
    label.setTypeface(Typeface.DEFAULT_BOLD)
    label.textSize = 12f
    row.addView(label)

    pill = row
    pillDot = dot
    pillLabel = label
    return row
  }

  private fun buildCard(activity: Activity): LinearLayout {
    val cardView = LinearLayout(activity)
    cardView.orientation = LinearLayout.VERTICAL
    cardView.background = roundedTop(activity, COLOR_SURFACE, 16f)
    cardView.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 12))
    cardView.visibility = View.GONE
    cardView.isClickable = true
    cardView.elevation = dp(activity, 8).toFloat()

    val header = LinearLayout(activity)
    header.orientation = LinearLayout.HORIZONTAL
    header.gravity = Gravity.CENTER_VERTICAL

    val title = TextView(activity)
    title.text = "PushSignal DevPanel"
    title.setTextColor(COLOR_TEXT)
    title.setTypeface(Typeface.DEFAULT_BOLD)
    title.textSize = 13f
    header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))

    val counterView = TextView(activity)
    counterView.setTextColor(COLOR_MUTED)
    counterView.textSize = 12f
    val counterParams = LinearLayout.LayoutParams(WRAP, WRAP)
    counterParams.marginEnd = dp(activity, 8)
    header.addView(counterView, counterParams)

    header.addView(actionButton(activity, "Копировать") { copyLog() })
    header.addView(actionButton(activity, "Очистить") { PushSignalDevLog.clear() })
    header.addView(actionButton(activity, "Свернуть") { setExpanded(false) })

    cardView.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

    val bodyContainer = LinearLayout(activity)
    bodyContainer.orientation = LinearLayout.VERTICAL

    val scrollView = ScrollView(activity)
    scrollView.isVerticalScrollBarEnabled = true
    scrollView.addView(bodyContainer, ViewGroup.LayoutParams(MATCH, WRAP))
    cardView.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0))

    card = cardView
    counter = counterView
    scroll = scrollView
    body = bodyContainer
    return cardView
  }

  private fun actionButton(activity: Activity, text: String, onClick: () -> Unit): TextView {
    val button = TextView(activity)
    button.text = text
    button.setTextColor(COLOR_ACCENT)
    button.textSize = 12f
    button.setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
    button.isClickable = true
    button.setOnClickListener { onClick() }
    return button
  }

  private fun setExpanded(value: Boolean) {
    if (expanded == value) {
      return
    }
    expanded = value
    card?.visibility = if (value) View.VISIBLE else View.GONE
    pill?.visibility = if (value) View.GONE else View.VISIBLE
    if (value) {
      if (bodyDirty) {
        rebuildBody()
      } else {
        scrollToBottomIfNeeded()
      }
    }
    updateSummary()
  }

  private fun render() {
    if (root == null) {
      return
    }
    updateSummary()
    if (expanded) {
      rebuildBody()
    } else {
      bodyDirty = true
    }
  }

  private fun updateSummary() {
    val count = PushSignalDevLog.count()
    pillLabel?.text = "PushSignal · $count"
    counter?.text = count.toString()
    pillDot?.background = oval(colorFor(PushSignalDevLog.lastLevel() ?: DevLogLevel.INFO))
  }

  private fun rebuildBody() {
    val context = host ?: return
    val bodyContainer = body ?: return
    val scrollView = scroll ?: return

    bodyDirty = false
    val wasAtBottom = scrollView.scrollY + scrollView.height >=
      bodyContainer.height - dp(context, 12)

    bodyContainer.removeAllViews()
    for (entry in PushSignalDevLog.snapshot()) {
      bodyContainer.addView(buildRow(context, entry), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    bodyContainer.post {
      val target = bodyContainer.height.coerceIn(0, maxBodyHeight)
      val params = scrollView.layoutParams
      if (params.height != target) {
        params.height = target
        scrollView.layoutParams = params
      }
      if (wasAtBottom) {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
      }
    }
  }

  private fun scrollToBottomIfNeeded() {
    val bodyContainer = body ?: return
    val scrollView = scroll ?: return
    bodyContainer.post { scrollView.fullScroll(View.FOCUS_DOWN) }
  }

  private fun buildRow(context: Context, entry: DevLogEntry): LinearLayout {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.VERTICAL
    row.setPadding(0, dp(context, 3), 0, dp(context, 3))

    val head = TextView(context)
    head.typeface = Typeface.MONOSPACE
    head.textSize = 11f
    head.setTextColor(colorFor(entry.level))
    head.text = "${timeFormat.format(Date(entry.timestamp))}  ${entry.level.label}  " +
      "${entry.category}  ${entry.title}"
    row.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

    val detail = entry.detail
    if (!detail.isNullOrBlank()) {
      val detailView = TextView(context)
      detailView.typeface = Typeface.MONOSPACE
      detailView.textSize = 11f
      detailView.setTextColor(COLOR_MUTED)
      detailView.text = "    $detail"
      row.addView(detailView, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    return row
  }

  private fun copyLog() {
    val context = host ?: return
    val text = PushSignalDevLog.snapshot().joinToString("\n") { entry ->
      "${timeFormat.format(Date(entry.timestamp))} ${entry.level.label} " +
        "[${entry.category}] ${entry.title}" + (entry.detail?.let { " — $it" } ?: "")
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("PushSignal DevPanel", text))
    Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
  }

  private fun colorFor(level: DevLogLevel): Int = when (level) {
    DevLogLevel.INFO -> COLOR_ACCENT
    DevLogLevel.SUCCESS -> COLOR_SUCCESS
    DevLogLevel.WARN -> COLOR_WARN
    DevLogLevel.ERROR -> COLOR_ERROR
  }

  private fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(context, radiusDp).toFloat()
      setColor(color)
    }
  }

  private fun roundedTop(context: Context, color: Int, radiusDp: Float): GradientDrawable {
    val radius = dp(context, radiusDp).toFloat()
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
      setColor(color)
    }
  }

  private fun oval(color: Int): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(color)
    }
  }

  private fun dp(context: Context, value: Number): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      context.resources.displayMetrics
    ).toInt()
  }

  private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
  private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
