package ai.lumen.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Health Connect queries for today's steps and last night's sleep. */
object HealthData {
    val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val PERMISSIONS = setOf(READ_STEPS, READ_SLEEP)

    fun available(ctx: Context): Boolean = try {
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Exception) {
        false
    }

    fun hasPermissions(ctx: Context, needed: Set<String> = PERMISSIONS): Boolean {
        if (!available(ctx)) return false
        return try {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = runBlocking { client.permissionController.getGrantedPermissions() }
            granted.containsAll(needed)
        } catch (_: Exception) {
            false
        }
    }

    fun hasStepsPermission(ctx: Context): Boolean = hasPermissions(ctx, setOf(READ_STEPS))

    /** Steps since local midnight, or null when Health Connect is unavailable or not permitted. */
    fun stepsToday(ctx: Context): Long? {
        if (!available(ctx)) return null
        return try {
            val client = HealthConnectClient.getOrCreate(ctx)
            runBlocking {
                val granted = client.permissionController.getGrantedPermissions()
                if (READ_STEPS !in granted) return@runBlocking null
                readSteps(client)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Phone status the Settings health call returns when "what" is missing. Same object on both entry points. */
    fun overview(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        )
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }.map { it.substringAfterLast('.') }
        return JSONObject()
            .put("assistant", Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true)
            .put("control", LumenAccess.instance != null)
            .put("notifications", NotificationAccess.enabled(ctx))
            .put("wake", WakeService.enabled(ctx)).put("samsung", Build.MANUFACTURER.equals("samsung", true)).put("wakeRunning", WakeService.instance != null)
            .put("installs", Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls())
            .put("battery", pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true)
            .put("dnd", ctx.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true)
            .put("systemSettings", android.provider.Settings.System.canWrite(ctx))
            .put("healthConnect", hasPermissions(ctx))
            .put("missing", JSONArray(missing)).put("version", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
            .put("driving", Driving.on(ctx)).put("drivingAuto", Driving.auto(ctx)).put("wakeScreenOff", WakeService.screenOff(ctx))
            .put("otpCopy", NotifPrefs.otpCopy(ctx)).put("callCard", NotifPrefs.callCard(ctx))
    }

    fun query(ctx: Context, what: String): JSONObject {
        val kind = what.trim().lowercase()
        val needed = when (kind) {
            "steps" -> setOf(READ_STEPS)
            "sleep" -> setOf(READ_SLEEP)
            "today" -> PERMISSIONS
            else -> return LumenAccess.err("bad_args")
        }
        if (!available(ctx)) return LumenAccess.err("health permission needed")
        return try {
            val client = HealthConnectClient.getOrCreate(ctx)
            runBlocking {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(needed)) return@runBlocking LumenAccess.err("health permission needed")
                val data = JSONObject()
                if (kind == "steps" || kind == "today") {
                    data.put("steps", readSteps(client))
                }
                if (kind == "sleep" || kind == "today") {
                    val sleep = readLastSleep(client)
                    data.put("sleep", sleep ?: JSONObject.NULL)
                }
                JSONObject().put("ok", true).put("data", data)
            }
        } catch (_: SecurityException) {
            LumenAccess.err("health permission needed")
        } catch (e: Exception) {
            Telemetry.warn("health", "read failed: ${e.message}")
            LumenAccess.err("health permission needed")
        }
    }

    private suspend fun readSteps(client: HealthConnectClient): Long {
        val now = Instant.now()
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        if (!now.isAfter(midnight)) return 0L
        val res = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(midnight, now)
            )
        )
        return res[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    private suspend fun readLastSleep(client: HealthConnectClient): JSONObject? {
        val now = Instant.now()
        val cutoff = now.minusSeconds(18L * 3600L)
        val lookback = now.minusSeconds(36L * 3600L)
        val res = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(lookback, now)
            )
        )
        val latest = res.records
            .filter { !it.endTime.isBefore(cutoff) && !it.endTime.isAfter(now) }
            .maxByOrNull { it.endTime }
            ?: return null
        val minutes = Duration.between(latest.startTime, latest.endTime).toMinutes().coerceAtLeast(0L)
        return JSONObject()
            .put("start", latest.startTime.toString())
            .put("end", latest.endTime.toString())
            .put("minutes", minutes)
    }
}

/** Minimal privacy rationale screen required by Health Connect. */
class HealthPrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07080D"))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val heading = TextView(this).apply {
            text = "Lumen Health Privacy"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val body = TextView(this).apply {
            text = "Lumen reads your step count and sleep sessions from Health Connect only when you ask about your steps or sleep. Health data stays private and is never sold or shared for advertising. You can revoke access at any time in Health Connect settings."
            setTextColor(Color.parseColor("#C7CBD6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(24))
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        root.addView(heading)
        root.addView(body)
        root.addView(close)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#07080D"))
            addView(root)
        })
    }
}
