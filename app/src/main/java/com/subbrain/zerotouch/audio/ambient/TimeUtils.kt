package com.subbrain.zerotouch.audio.ambient

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun todayString(): String = dateFormat.format(Date())
}
