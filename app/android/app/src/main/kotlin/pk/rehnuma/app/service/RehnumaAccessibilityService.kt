package pk.rehnuma.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import pk.rehnuma.app.config.AppConfig

/**
 * Entry point of the Kotlin core (SPEC 5.1). Reads the screen only while a guidance session is
 * active (rule 4). It never performs actions or gestures for the user (rule 1).
 */
class RehnumaAccessibilityService : AccessibilityService() {

    private lateinit var notification: SessionNotification
    private var bubbleVisible = false
    private var sessionActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        notification = SessionNotification(this) { hideBubble() }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!sessionActive || event == null) return
    }

    override fun onInterrupt() {
        // Nothing to interrupt: Rehnuma gives no spoken accessibility feedback of its own.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        sessionActive = false
        bubbleVisible = false
        if (::notification.isInitialized) notification.release()
    }

    // ---- Called from the Flutter bridge (main thread) ----

    fun showBubble() {
        bubbleVisible = true
        notification.show()
    }

    fun hideBubble() {
        sessionActive = false
        bubbleVisible = false
        notification.hide()
    }

    fun isBubbleVisible(): Boolean = bubbleVisible

    fun startQuickTask(goal: String) {
        if (!bubbleVisible) showBubble()
    }

    fun onConfigChanged(config: AppConfig) {}

    fun setDebugBoxes(enabled: Boolean) {}

    fun saveSnapshotAfter(delayMs: Long) {}

    fun metricsSummary(): Map<String, Any?> = metricsSummary(this)

    companion object {
        @Volatile
        var instance: RehnumaAccessibilityService? = null
            private set

        /** True when the service is running or enabled in system settings. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, RehnumaAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun metricsSummary(context: Context): Map<String, Any?> = emptyMap()

        fun exportMetrics(activity: Activity): String? = null

        fun resetLearning(context: Context) {}
    }
}
