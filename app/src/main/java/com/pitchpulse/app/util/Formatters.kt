package com.pitchpulse.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("MM月dd日 EEE", Locale.CHINA) }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.CHINA) }
    private val fileStampFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    fun dateTime(ms: Long): String = dateTimeFormat.get()!!.format(Date(ms))
    fun date(ms: Long): String = dateFormat.get()!!.format(Date(ms))
    fun time(ms: Long): String = timeFormat.get()!!.format(Date(ms))
    fun fileStamp(ms: Long): String = fileStampFormat.get()!!.format(Date(ms))

    fun km(meters: Float): String = String.format(Locale.US, "%.2f", meters / 1000f)
    fun meters(meters: Float): String = String.format(Locale.US, "%.0f", meters)
    fun oneDecimal(v: Float): String = String.format(Locale.US, "%.1f", v)
    fun percent(share: Float): String = String.format(Locale.US, "%.0f%%", share * 100f)
    fun minutes(ms: Long): String = String.format(Locale.US, "%.1f", ms / 60_000f)

    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        val sec = (ms / 1000) % 60
        return String.format(Locale.US, "%d:%02d", totalMin, sec)
    }
}
