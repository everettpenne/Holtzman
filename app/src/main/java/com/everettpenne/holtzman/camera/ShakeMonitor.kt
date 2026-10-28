package com.everettpenne.holtzman.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * How fast the phone is turning, in radians a second, smoothed over about a quarter of a second (the root mean square of the gyroscope's magnitude):
 * the hand shake an exposure will have to live with. Zero when the phone has no gyroscope.
 */
class ShakeMonitor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var meanSquare = 0f
    private var lastNanos = 0L
    @Volatile private var smoothed = 0f

    val rate: Float get() = smoothed

    fun start() { gyro?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { manager.unregisterListener(this); lastNanos = 0L }

    override fun onSensorChanged(e: SensorEvent) {
        val sq = e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]
        val dt = if (lastNanos == 0L) 0.02f else ((e.timestamp - lastNanos) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNanos = e.timestamp
        val alpha = dt / (0.25f + dt)
        meanSquare += alpha * (sq - meanSquare)
        smoothed = sqrt(meanSquare)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
