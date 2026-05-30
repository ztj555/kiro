package com.autodial.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class StatsFragment : Fragment() {

    private lateinit var todayCount: TextView
    private lateinit var totalCount: TextView
    private lateinit var todayDuration: TextView
    private lateinit var totalDuration: TextView
    private lateinit var todayLuck: TextView
    private lateinit var totalLuck: TextView
    private lateinit var chartContainer: LinearLayout
    private lateinit var dateLabels: LinearLayout
    private lateinit var detailList: LinearLayout

    // 主题变更监听
    private val themeListener: () -> Unit = {
        if (isAdded) {
            applyTheme()
            loadStats()
        }
    }

    private val newDialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshIfNeeded()
        }
    }

    // 通话结束时延迟1秒刷新统计
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, 1000)
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshIfNeeded() }

    private val dayOfWeekFormat = SimpleDateFormat("E", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        todayCount = view.findViewById(R.id.statsTodayCount)
        totalCount = view.findViewById(R.id.statsTotalCount)
        todayDuration = view.findViewById(R.id.statsTodayDuration)
        totalDuration = view.findViewById(R.id.statsTotalDuration)
        todayLuck = view.findViewById(R.id.statsTodayLuck)
        totalLuck = view.findViewById(R.id.statsTotalLuck)
        chartContainer = view.findViewById(R.id.statsChartContainer)
        dateLabels = view.findViewById(R.id.statsDateLabels)
        detailList = view.findViewById(R.id.statsDetailList)

        // 注册新拨号广播（应用内广播，使用 NOT_EXPORTED）
        try {
            ContextCompat.registerReceiver(requireActivity(), newDialReceiver,
                IntentFilter("com.autodial.NEW_DIAL"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        // 注册通话结束广播（应用内广播，使用 NOT_EXPORTED）
        try {
            ContextCompat.registerReceiver(requireActivity(), callEndedReceiver,
                IntentFilter("com.autodial.CALL_ENDED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        refreshIfNeeded()

        // 应用主题
        applyTheme()

        // 注册主题变更监听
        ThemeManager.addOnThemeChangedListener(themeListener)
    }

    override fun onResume() {
        super.onResume()
        refreshIfNeeded()
    }

    fun onThemeChanged() {
        // 主题变更由 themeListener 处理
        themeListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ThemeManager.removeOnThemeChangedListener(themeListener)
        refreshHandler.removeCallbacks(refreshRunnable)
        try { requireActivity().unregisterReceiver(newDialReceiver) } catch (_: Exception) {}
        try { requireActivity().unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
    }

    fun refreshIfNeeded() {
        if (isAdded && !isDetached) {
            loadStats()
        }
    }

    /** 格式化秒数为分钟字符串 */
    private fun formatMinutes(seconds: Long): String {
        if (seconds <= 0) return "0.0"
        val mins = seconds / 60.0
        return if (mins == mins.toLong().toDouble()) {
            mins.toLong().toString()
        } else {
            String.format("%.1f", mins)
        }
    }

    private fun loadStats() {
        if (!isAdded) return
        val db = CallLogDb(requireContext())
        try {
            // 概览数据（通次）
            val today = db.getTodayCount()
            val total = db.getTotalCount()
            todayCount.text = today.toString()
            totalCount.text = total.toString()

            // 近7天统计（含通时）
            val stats = db.getDailyDurationStats(requireContext(), 7)

            // 今日通时
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val todayStats = stats.find { it.date == todayStr }
            val todaySec = todayStats?.totalDurationSec ?: 0
            todayDuration.text = formatMinutes(todaySec)

            // 累计通时（所有天通时之和）
            val totalSec = stats.sumOf { it.totalDurationSec }
            totalDuration.text = formatMinutes(totalSec)

            // 财运统计
            loadLuckStats()

            buildChart(stats)
            buildDetailList(stats)
        } finally {
            db.close()  // D2修复: 确保 CallLogDb 关闭，防止数据库连接泄漏
        }
    }

    /** 加载财运统计（成功通话次数）- 在后台线程执行，防止 ANR */
    private fun loadLuckStats() {
        if (!isAdded) return
        val ctx = requireContext()

        Thread {
            var todayLuckCount = 0
            var totalLuckCount = 0

            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALL_LOG)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val todayCal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val todayStart = todayCal.timeInMillis

                    // 今日财运
                    val todayCursor = ctx.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(android.provider.CallLog.Calls.DURATION),
                        "${android.provider.CallLog.Calls.DATE} >= ? AND ${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DURATION} > 0",
                        arrayOf(todayStart.toString(), android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
                        null
                    )
                    todayLuckCount = todayCursor?.count ?: 0
                    todayCursor?.close()

                    // 累计财运
                    val totalCursor = ctx.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(android.provider.CallLog.Calls.DURATION),
                        "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DURATION} > 0",
                        arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
                        null
                    )
                    totalLuckCount = totalCursor?.count ?: 0
                    totalCursor?.close()
                } catch (_: Exception) {}
            }

            val finalToday = todayLuckCount
            val finalTotal = totalLuckCount
            // 回到主线程更新 UI
            refreshHandler.post {
                if (isAdded) {
                    todayLuck.text = finalToday.toString()
                    totalLuck.text = finalTotal.toString()
                }
            }
        }.start()
    }

    private fun buildChart(stats: List<CallLogDb.DayStats>) {
        chartContainer.removeAllViews()
        dateLabels.removeAllViews()

        val colors = ThemeManager.getColors(requireContext())
        val maxCount = (stats.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

        val barColors = intArrayOf(
            Color.parseColor(colors.gold),
            Color.parseColor(colors.goldLight),
            Color.parseColor(colors.gold),
            Color.parseColor(colors.green),
            Color.parseColor(colors.gold),
            Color.parseColor(colors.goldLight),
            Color.parseColor("#4F8EF7")  // 今日（最后一天）用蓝色突出
        )

        val maxBarHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 90f, resources.displayMetrics).toInt()
        val barWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        val minHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        val chartHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 140f, resources.displayMetrics).toInt()

        for (i in stats.indices) {
            val s = stats[i]

            // 柱子
            val barWrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, chartHeightPx, 1f)
            }

            // 通时标签（柱子上方，如 "12分"）
            val durationLabel = TextView(requireContext()).apply {
                text = if (s.totalDurationSec > 0) formatMinutes(s.totalDurationSec) + "分" else ""
                textSize = 9f
                setTextColor(Color.parseColor(colors.text2))
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2 }
            }
            barWrapper.addView(durationLabel)

            // 数量标签
            val countLabel = TextView(requireContext()).apply {
                text = if (s.count > 0) s.count.toString() else ""
                textSize = 11f
                setTextColor(Color.parseColor(colors.text2))
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            barWrapper.addView(countLabel)

            // 柱子本身
            val actualBarHeight = if (s.count > 0) {
                ((s.count.toFloat() / maxCount) * maxBarHeightPx).toInt().coerceAtLeast(barWidthPx)
            } else {
                minHeightPx
            }
            val bar = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(barWidthPx, actualBarHeight).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setBackgroundColor(if (s.count > 0) barColors[i] else Color.parseColor(colors.bg3))
            }
            barWrapper.addView(bar)

            chartContainer.addView(barWrapper)

            // 日期标签
            val dateLabel = TextView(requireContext()).apply {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, -(6 - i))
                val dayOfWeek = dayOfWeekFormat.format(cal.time)
                val dateStr = s.date.substring(5) // MM-dd
                text = "$dayOfWeek\n$dateStr"
                textSize = 10f
                setTextColor(Color.parseColor(colors.text2))
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { topMargin = 8 }
                setPadding(0, 0, 0, 0)
            }
            dateLabels.addView(dateLabel)
        }
    }

    private fun buildDetailList(stats: List<CallLogDb.DayStats>) {
        detailList.removeAllViews()
        val colors = ThemeManager.getColors(requireContext())

        // 倒序显示（最近的在上面）
        val reversed = stats.reversed()
        // 修复：today 必须在此处定义，不能引用不存在的类属性
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        for ((rowIndex, s) in reversed.withIndex()) {
            val isToday = s.date == today
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(if (isToday) 13 else 16, 14, 16, 14)
                // 今日行：蓝色左边框 + 微蓝背景；其他行：交替背景
                setBackgroundColor(
                    when {
                        isToday -> Color.parseColor("#0D1A2E")
                        rowIndex % 2 == 0 -> Color.parseColor(colors.bg)
                        else -> Color.parseColor(colors.bg2)
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 今日行：左侧蓝色竖条
            if (isToday) {
                val accent = View(requireContext()).apply {
                    setBackgroundColor(Color.parseColor("#4F8EF7"))
                    layoutParams = LinearLayout.LayoutParams(3, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = 13
                    }
                }
                row.addView(accent)
            }

            // 日期
            val dateText = TextView(requireContext()).apply {
                text = s.date
                textSize = 14f
                setTextColor(Color.parseColor(if (isToday) "#4F8EF7" else colors.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            if (isToday) {
                dateText.text = "${s.date}（今天）"
                dateText.textSize = 13f
            }

            // 通时（分钟）
            val durationText = TextView(requireContext()).apply {
                text = "${formatMinutes(s.totalDurationSec)}分"
                textSize = 13f
                setTextColor(
                    if (s.totalDurationSec > 0) Color.parseColor(colors.green)
                    else Color.parseColor(colors.text2)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 拨号次数
            val countText = TextView(requireContext()).apply {
                text = "${s.count} 次"
                textSize = 14f
                setTextColor(
                    if (s.count > 0) Color.parseColor(colors.gold)
                    else Color.parseColor(colors.text2)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(dateText)
            row.addView(durationText)
            row.addView(countText)
            detailList.addView(row)

            // 分割线
            val divider = View(requireContext()).apply {
                setBackgroundColor(Color.parseColor(colors.bg3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    marginStart = 16
                    marginEnd = 16
                }
            }
            detailList.addView(divider)
        }
    }

    private fun applyTheme() {
        if (!isAdded) return
        val colors = ThemeManager.getColors(requireContext())
        ThemeManager.applyToView(requireView(), colors)
    }
}
