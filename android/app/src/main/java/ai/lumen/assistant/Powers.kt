package ai.lumen.assistant

import android.bluetooth.BluetoothManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Phone abilities beyond the basics: texts, contacts, music, videos, Bluetooth, steps, the phone line. Each runs off the UI thread. */
object Powers {
    fun smsInbox(ctx: Context, query: String, limit: Int): JSONArray {
        val out = JSONArray()
        val sel = if (query.isBlank()) null else "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        val names = HashMap<String, String>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            sel, args, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val number = c.getString(0) ?: ""
                val name = names.getOrPut(number) { contactName(ctx, number) }
                out.put(
                    JSONObject().put("number", number).put("name", name).put("text", (c.getString(1) ?: "").take(400))
                        .put("at", Instant.ofEpochMilli(c.getLong(2)).toString())
                        .put("sent", c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT).put("unread", c.getInt(4) == 0)
                )
            }
        }
        return out
    }

    private fun contactName(ctx: Context, number: String): String = try {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
    } catch (_: Exception) { "" }

    /** "Search my life": texts, the notification history, calls, contacts, calendar and photo albums, all on the phone. */
    fun lifeSearch(ctx: Context, query: String, fromIso: String, toIso: String, limit: Int): JSONObject {
        val stop = setOf("the", "a", "an", "my", "me", "i", "about", "from", "with", "that", "this", "to", "of", "in", "on", "for", "and", "what", "when", "where", "did", "was")
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}@.+'-]+")).filter { it.length >= 2 && it !in stop }.take(6)
        val from = try { Instant.parse(fromIso).toEpochMilli() } catch (_: Exception) { 0L }
        val to = try { Instant.parse(toIso).toEpochMilli() } catch (_: Exception) { Long.MAX_VALUE }
        val out = JSONObject().put("query", words.joinToString(" "))
        fun hit(vararg s: String?) = words.isEmpty() || s.joinToString(" ").lowercase().let { h -> words.all { h.contains(it) } }
        fun section(name: String, block: () -> JSONArray) { try { val a = block(); if (a.length() > 0) out.put(name, a) } catch (_: SecurityException) { out.put(name + "_off", true) } catch (_: Exception) {} }
        val first = words.firstOrNull() ?: ""
        section("texts") {
            val a = JSONArray()
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                if (first.isEmpty()) "${Telephony.Sms.DATE} BETWEEN ? AND ?" else "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?) AND ${Telephony.Sms.DATE} BETWEEN ? AND ?",
                if (first.isEmpty()) arrayOf("$from", "$to") else arrayOf("%$first%", "%$first%", "$from", "$to"), "${Telephony.Sms.DATE} DESC")?.use { c ->
                val names = HashMap<String, String>()
                while (c.moveToNext() && a.length() < limit) {
                    val num = c.getString(0) ?: ""; val body = c.getString(1) ?: ""; val who = names.getOrPut(num) { contactName(ctx, num) }
                    if (hit(body, num, who)) a.put(JSONObject().put("from", who.ifEmpty { num }).put("text", body.take(300)).put("at", Instant.ofEpochMilli(c.getLong(2)).toString()).put("sent", c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT))
                }
            }; a
        }
        section("notifications") { Triggers.search(ctx, words, limit) }
        section("calls") {
            val a = JSONArray()
            ctx.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION, android.provider.CallLog.Calls.TYPE),
                "${android.provider.CallLog.Calls.DATE} BETWEEN ? AND ?", arrayOf("$from", "$to"), "${android.provider.CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && a.length() < limit) { val n = c.getString(0) ?: ""; val num = c.getString(1) ?: ""
                    if (hit(n, num)) a.put(JSONObject().put("who", n.ifEmpty { num }).put("at", Instant.ofEpochMilli(c.getLong(2)).toString()).put("seconds", c.getLong(3)).put("type", when (c.getInt(4)) { 1 -> "incoming"; 2 -> "outgoing"; else -> "missed" })) }
            }; a
        }
        if (first.isNotEmpty()) section("contacts") {
            val a = JSONArray()
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(first)), arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                while (c.moveToNext() && a.length() < limit) a.put(JSONObject().put("name", c.getString(0) ?: "").put("number", c.getString(1) ?: ""))
            }; a
        }
        section("calendar") {
            val a = JSONArray(); val now = System.currentTimeMillis()
            val b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(b, maxOf(from, now - 400L * 86400_000)); ContentUris.appendId(b, minOf(to, now + 400L * 86400_000))
            ctx.contentResolver.query(b.build(), arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.EVENT_LOCATION), null, null, "${android.provider.CalendarContract.Instances.BEGIN} DESC")?.use { c ->
                while (c.moveToNext() && a.length() < limit) { val t = c.getString(0) ?: ""; val loc = c.getString(2) ?: ""
                    if (hit(t, loc)) a.put(JSONObject().put("title", t).put("at", Instant.ofEpochMilli(c.getLong(1)).toString()).put("where", loc)) }
            }; a
        }
        section("photos") {
            val a = JSONArray()
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?", arrayOf("$from", "$to"), "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                val dated = fromIso.isNotEmpty() || toIso.isNotEmpty()
                while (c.moveToNext() && a.length() < limit) { val album = c.getString(2) ?: ""; val file = c.getString(3) ?: ""
                    if (dated && words.isEmpty() || hit(album, file) || (dated && words.all { w -> album.lowercase().contains(w) || file.lowercase().contains(w) })) a.put(JSONObject().put("id", c.getLong(0)).put("at", Instant.ofEpochMilli(c.getLong(1)).toString()).put("album", album)) }
            }; a
        }
        return out
    }

    fun smsSend(ctx: Context, number: String, text: String): JSONObject {
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        sm.sendMultipartTextMessage(number, null, parts, null, null)
        return JSONObject().put("sent", true).put("parts", parts.size)
    }

    fun contactSave(ctx: Context, name: String, phone: String, email: String): JSONObject {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build()
        )
        if (phone.isNotBlank()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
        if (email.isNotBlank()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email).build()
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return JSONObject().put("saved", name)
    }

    /** Songs or videos stored on the phone, newest first, optionally matching a word. */
    fun media(ctx: Context, video: Boolean, query: String, limit: Int): JSONArray {
        val out = JSONArray()
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = if (video) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.DATE_ADDED)
        else arrayOf(MediaStore.MediaColumns._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
        val conds = mutableListOf<String>(); val args = mutableListOf<String>()
        if (!video) conds += "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        if (query.isNotBlank()) {
            conds += if (video) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)"
            repeat(if (video) 1 else 3) { args += "%$query%" }
        }
        ctx.contentResolver.query(uri, cols, conds.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null }, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val o = JSONObject().put("id", c.getLong(0)).put("title", c.getString(1) ?: "").put("seconds", c.getLong(2) / 1000)
                    .put("added", Instant.ofEpochSecond(c.getLong(3)).toString().take(10))
                if (!video) o.put("artist", c.getString(4) ?: "").put("album", c.getString(5) ?: "")
                out.put(o)
            }
        }
        return out
    }

    fun playIntent(video: Boolean, id: Long): Intent {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(base, id), if (video) "video/*" else "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("MissingPermission")
    fun bluetooth(ctx: Context): JSONObject {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return JSONObject().put("available", false)
        val paired = JSONArray()
        for (d in adapter.bondedDevices ?: emptySet()) paired.put(JSONObject().put("name", d.name ?: d.address).put("kind", d.bluetoothClass?.majorDeviceClass ?: 0))
        return JSONObject().put("available", true).put("on", adapter.isEnabled).put("paired", paired)
    }

    /** Steps counted by the phone's step sensor since it last restarted (the sensor keeps no daily history). */
    fun steps(ctx: Context): JSONObject {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return JSONObject().put("available", false)
        var value = -1f
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { value = e.values[0]; latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(4, TimeUnit.SECONDS)
        sm.unregisterListener(listener)
        val since = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString()
        // today's count: the first reading each day becomes that day's starting point
        val prefs = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        val day = java.time.LocalDate.now().toString()
        if (value >= 0 && (prefs.getString("stepsDay", "") != day || prefs.getFloat("stepsBase", 0f) > value)) prefs.edit().putString("stepsDay", day).putFloat("stepsBase", value).apply()
        return JSONObject().put("available", value >= 0).put("sinceRestart", value.toInt()).put("restartedAt", since)
            .put("todaySinceFirstCheck", if (value >= 0) (value - prefs.getFloat("stepsBase", value)).toInt() else 0)
    }

    @Suppress("MissingPermission")
    fun phoneInfo(ctx: Context): JSONObject {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val o = JSONObject()
            .put("carrier", tm?.networkOperatorName ?: "").put("roaming", tm?.isNetworkRoaming ?: false)
            .put("connection", when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            })
        try {
            o.put("network", when (tm?.dataNetworkType) { 20 -> "5G"; 13 -> "LTE"; 0, null -> "unknown"; else -> "3G or older" })
            o.put("inCall", ctx.getSystemService(TelecomManager::class.java)?.isInCall ?: false)
        } catch (_: SecurityException) {}
        return o
    }

    @Suppress("MissingPermission", "DEPRECATION")
    fun endCall(ctx: Context): JSONObject {
        val ended = Build.VERSION.SDK_INT >= 28 && (ctx.getSystemService(TelecomManager::class.java)?.endCall() ?: false)
        return JSONObject().put("ended", ended)
    }
}
