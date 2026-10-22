package com.agani.syncup.forest

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Gentle buzzes: a light tick, and a soft double at the end of something (Wellbeing → Breaks). */
internal object Buzz {
    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Vibrator::class.java)
    }

    fun tick(ctx: Context) = buzz(ctx, longArrayOf(0, 35), intArrayOf(0, 90))

    fun end(ctx: Context) = buzz(ctx, longArrayOf(0, 70, 160, 70), intArrayOf(0, 120, 0, 90))

    private fun buzz(ctx: Context, timings: LongArray, amps: IntArray) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(timings, if (v.hasAmplitudeControl()) amps else IntArray(amps.size) { if (it % 2 == 1) 255 else 0 }, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        }
    }
}
