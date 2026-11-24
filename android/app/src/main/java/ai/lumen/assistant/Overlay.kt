package ai.lumen.assistant

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import org.json.JSONObject

/**
 * Lumen as an assistant panel: a glass sheet at the bottom of whatever app is open, orb in the middle, answers and
 * cards drawn on top of that app. Touches above the sheet still reach the app; a tap there closes the panel.
 */
class OverlayActivity : MainActivity() {
    override val overlay = true
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.30).toInt())
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    override fun onPanel(msg: JSONObject) {
        runOnUiThread {
            when (msg.optString("type")) {
                "overlay" -> {
                    val px = (msg.optDouble("h", 0.0) * resources.displayMetrics.density).toInt()
                    val max = (resources.displayMetrics.heightPixels * 0.78).toInt()
                    if (px > 0) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, px.coerceIn(200, max))
                }
                "busy" -> busy = msg.optBoolean("on")
                "close" -> finishAndRemoveTask()
                "full" -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finishAndRemoveTask() }
                "front" -> try { startActivity(Intent(this, OverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE && !busy) { finishAndRemoveTask(); return true }
        return super.onTouchEvent(event)
    }
}
