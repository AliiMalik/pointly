package pk.rehnuma.app.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.service.RehnumaAccessibilityService

/** MethodChannel `rehnuma/control` and EventChannel `rehnuma/events` (SPEC 10.1, 10.2). */
class RehnumaChannel(private val activity: Activity) : MethodChannel.MethodCallHandler {

    fun register(engine: FlutterEngine) {
        val messenger = engine.dartExecutor.binaryMessenger
        MethodChannel(messenger, CONTROL).setMethodCallHandler(this)
        EventChannel(messenger, EVENTS).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                RehnumaEvents.attach { events.success(it) }
            }

            override fun onCancel(arguments: Any?) = RehnumaEvents.detach()
        })
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = activity.applicationContext
        val service = RehnumaAccessibilityService.instance
        when (call.method) {
            "isServiceEnabled" -> result.success(RehnumaAccessibilityService.isEnabled(ctx))
            "openAccessibilitySettings" -> {
                // The only allowed launch (SPEC rule 1): Android's own accessibility settings page.
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                result.success(null)
            }
            "showBubble" -> withService(service, result) { it.showBubble(); result.success(null) }
            "hideBubble" -> {
                service?.hideBubble()
                result.success(null)
            }
            "isBubbleVisible" -> result.success(service?.isBubbleVisible() == true)
            "startQuickTask" -> {
                val goal = call.argument<String>("goal")?.trim().orEmpty()
                if (goal.isEmpty()) {
                    result.error("BAD_ARGS", "goal is required", null)
                    return
                }
                withService(service, result) {
                    it.startQuickTask(goal)
                    result.success(null)
                }
            }
            "setConfig" -> {
                val cfg = AppConfig.update(ctx, call.arguments as? Map<*, *> ?: emptyMap<String, Any>())
                service?.onConfigChanged(cfg)
                result.success(null)
            }
            "getConfig" -> result.success(AppConfig.load(ctx).toMap())
            "getMetricsSummary" -> result.success(service?.metricsSummary() ?: RehnumaAccessibilityService.metricsSummary(ctx))
            "exportMetrics" -> result.success(RehnumaAccessibilityService.exportMetrics(activity))
            "resetLearning" -> {
                RehnumaAccessibilityService.resetLearning(ctx)
                result.success(null)
            }
            "setDebugBoxes" -> {
                val enabled = call.argument<Boolean>("enabled") == true
                AppConfig.update(ctx, mapOf("debugBoxes" to enabled))
                service?.setDebugBoxes(enabled)
                result.success(null)
            }
            "saveSnapshot" -> {
                val delayMs = (call.argument<Int>("delayMs") ?: 5000).toLong()
                withService(service, result) {
                    it.saveSnapshotAfter(delayMs)
                    result.success(null)
                }
            }
            "getState" -> result.success(RehnumaEvents.last)
            "getPermissions" -> result.success(
                mapOf(
                    "accessibility" to RehnumaAccessibilityService.isEnabled(ctx),
                    "microphone" to granted(Manifest.permission.RECORD_AUDIO),
                    "notifications" to (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
                ),
            )
            "requestPermission" -> {
                val permission = when (call.argument<String>("name")) {
                    "microphone" -> Manifest.permission.RECORD_AUDIO
                    "notifications" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
                    else -> null
                }
                if (permission != null && !granted(permission)) {
                    ActivityCompat.requestPermissions(activity, arrayOf(permission), REQUEST_CODE)
                }
                result.success(null) // Flutter re-reads getPermissions when the app resumes.
            }
            else -> result.notImplemented()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private inline fun withService(
        service: RehnumaAccessibilityService?,
        result: MethodChannel.Result,
        block: (RehnumaAccessibilityService) -> Unit,
    ) {
        if (service == null) {
            result.error("SERVICE_OFF", "Accessibility service is not running", null)
        } else {
            block(service)
        }
    }

    companion object {
        const val CONTROL = "rehnuma/control"
        const val EVENTS = "rehnuma/events"
        private const val REQUEST_CODE = 7301
    }
}
