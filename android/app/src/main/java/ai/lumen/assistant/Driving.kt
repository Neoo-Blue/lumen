package ai.lumen.assistant

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Driving: when the phone joins a car's Bluetooth, Lumen goes hands-free (listens with the screen off, speaks its
 * answers, confirms by voice) and steps back when the car disconnects.
 */
object Driving {
    private fun prefs(c: Context) = c.getSharedPreferences("lumen", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("driving", false)
    fun auto(c: Context) = prefs(c).getBoolean("drivingAuto", true)
    fun setAuto(c: Context, v: Boolean) = prefs(c).edit().putBoolean("drivingAuto", v).apply()
    fun set(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("driving", v).apply()
        Telemetry.info("driving", if (v) "driving mode on" else "driving mode off")
        // hands-free needs the listener; it may now listen with the screen off
        if (v && WakeService.enabled(c) && WakeService.instance == null) try { WakeService.setEnabled(c, true) } catch (_: Exception) {}
        WakeService.instance?.recheck()
    }
}

class CarReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Driving.auto(ctx)) return
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val cls = try { dev?.bluetoothClass?.deviceClass } catch (_: Exception) { null }
        val car = cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        if (!car) return
        Driving.set(ctx, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
    }
}
