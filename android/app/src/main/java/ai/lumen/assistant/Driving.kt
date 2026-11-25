package ai.lumen.assistant

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Volatile private var parking = false

    /** One fix when a car radio disconnects. Stored under prefs "lumen" / "parked". No prompt: permission must already be on. Blocks the caller. */
    fun rememberPark(ctx: Context) {
        val app = ctx.applicationContext
        if (!locationAllowed(app)) { Telemetry.info("driving", "parking spot skipped: location permission is off"); return }
        synchronized(this) { if (parking) return; parking = true }
        try { storeFix(app) } catch (_: Exception) {} finally { parking = false }
    }

    fun parked(ctx: Context): JSONObject {
        val raw = prefs(ctx).getString("parked", null) ?: return LumenAccess.err("no parking spot saved")
        val o = try { JSONObject(raw) } catch (_: Exception) { return LumenAccess.err("no parking spot saved") }
        if (!o.has("lat") || !o.has("lon")) return LumenAccess.err("no parking spot saved")
        return JSONObject().put("ok", true).put("data", JSONObject()
            .put("lat", o.getDouble("lat")).put("lon", o.getDouble("lon"))
            .put("accuracy", o.optDouble("accuracy")).put("at", o.optLong("t")))
    }

    private fun locationAllowed(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun storeFix(ctx: Context) {
        if (!locationAllowed(ctx)) return
        val manager = ctx.getSystemService(LocationManager::class.java) ?: return
        val loc = (if (Build.VERSION.SDK_INT >= 30) currentFix(manager) else null) ?: lastKnown(manager)
        if (loc == null) { Telemetry.info("driving", "no parking fix"); return }
        val o = JSONObject().put("lat", loc.latitude).put("lon", loc.longitude)
            .put("accuracy", loc.accuracy.toDouble()).put("t", System.currentTimeMillis())
        prefs(ctx).edit().putString("parked", o.toString()).apply()
        Telemetry.info("driving", "saved a parking spot")
    }

    @SuppressLint("MissingPermission")
    private fun currentFix(manager: LocationManager): Location? {
        val provider = bestProvider(manager) ?: return null
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val ex = Executors.newSingleThreadExecutor()
        val cancel = CancellationSignal()
        try {
            manager.getCurrentLocation(provider, cancel, ex) { loc -> fresh = loc; latch.countDown() }
            if (!latch.await(20, TimeUnit.SECONDS)) cancel.cancel()
        } catch (_: Exception) {
        } finally { ex.shutdown() }
        return fresh
    }

    private fun bestProvider(manager: LocationManager): String? {
        if (Build.VERSION.SDK_INT >= 31) {
            try { if (manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER } catch (_: Exception) {}
        }
        val named = try { manager.getBestProvider(Criteria().apply { accuracy = Criteria.ACCURACY_FINE }, true) } catch (_: Exception) { null }
        if (!named.isNullOrEmpty()) return named
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull {
            try { manager.isProviderEnabled(it) } catch (_: Exception) { false }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }
}

class CarReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val cls = try { dev?.bluetoothClass?.deviceClass } catch (_: Exception) { null }
        val car = cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        if (!car) return
        val on = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val name = try { dev?.name ?: "" } catch (_: Exception) { "" }
        val audioCar = cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
        val pending = goAsync()
        val app = ctx.applicationContext
        Thread({
            try {
                Moments.note(app, if (on) "car_on" else "car_off", name.take(60), if (audioCar) "car" else "handsfree") // for habits: when the person usually sets off
                if (Driving.auto(app)) Driving.set(app, on)
                LocalRules.onCar(app, on)
                if (!on && audioCar) {
                    val park = Thread({ Driving.rememberPark(app) }, "lumen-park")
                    park.isDaemon = true
                    park.start()
                    park.join(25_000L) // hold the broadcast open until the fix lands, but not past about 25s
                }
            } catch (_: Exception) {
            } finally {
                try { pending.finish() } catch (_: Exception) {}
            }
        }, "lumen-car").apply { isDaemon = true }.start()
    }
}


/**
 * Habits: the one thing noted is the phone joining or leaving the car, with its time, sent to Lumen's server (which
 * finds when the person usually sets off). Kept on the phone while offline and sent later.
 */
object Moments {
    private fun prefs(c: Context) = c.getSharedPreferences("moments", Context.MODE_PRIVATE)
    fun note(ctx: Context, kind: String, dev: String = "", cls: String = "") {
        val app = ctx.applicationContext
        synchronized(this) { val q = org.json.JSONArray(prefs(app).getString("q", "[]")); q.put(org.json.JSONObject().put("kind", kind).put("t", System.currentTimeMillis()).put("dev", dev).put("cls", cls))
            while (q.length() > 50) q.remove(0); prefs(app).edit().putString("q", q.toString()).apply() }
        Thread { flush(app) }.start()
    }
    @Synchronized fun flush(ctx: Context) {
        val q = org.json.JSONArray(prefs(ctx).getString("q", "[]")); if (q.length() == 0) return
        val cookie = try { android.webkit.CookieManager.getInstance().getCookie(LumenOrigin.HOME) ?: "" } catch (_: Exception) { "" }
        if (cookie.isEmpty()) return
        var sent = 0
        for (i in 0 until q.length()) {
            try {
                val c = java.net.URL(LumenOrigin.HOME + "/moments").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("cookie", cookie)
                c.outputStream.use { it.write(q.getJSONObject(i).toString().toByteArray()) }
                val code = c.responseCode; c.disconnect(); if (code != 200) break; sent++
            } catch (_: Exception) { break }
        }
        val left = org.json.JSONArray(); for (i in sent until q.length()) left.put(q.get(i)); prefs(ctx).edit().putString("q", left.toString()).apply()
    }
}
