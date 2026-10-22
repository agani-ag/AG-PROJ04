package com.agani.syncup.forest

import java.util.Calendar

/** Days as yyyymmdd ints (java.time needs Android 8). Tools → Wellbeing counts its days with these. */
internal object FDays {
    fun of(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }
    fun today(): Int = of(System.currentTimeMillis())
    fun add(day: Int, n: Int): Int = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100)
        add(Calendar.DAY_OF_YEAR, n)
    }.let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }
    fun weekday(day: Int): Int = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100)
    }.get(Calendar.DAY_OF_WEEK) // 1 = Sunday
    fun month(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    fun year(): Int = Calendar.getInstance().get(Calendar.YEAR)
    fun hour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    fun dateText(ms: Long): String = Calendar.getInstance().apply { timeInMillis = ms }.let {
        "${it.get(Calendar.DAY_OF_MONTH)} ${arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[it.get(Calendar.MONTH)]}"
    }
}
