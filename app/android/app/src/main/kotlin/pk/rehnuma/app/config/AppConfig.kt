package pk.rehnuma.app.config

import android.content.Context

/** User settings (SPEC 10 item 4). Stored in SharedPreferences, read by the Kotlin core. */
data class AppConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val appKey: String = DEFAULT_APP_KEY,
    val voiceIn: String = VOICE_DEVICE,      // "device" | "cloud"
    val voiceOut: String = VOICE_DEVICE,     // "device" | "cloud"
    val caption: String = CAPTION_ROMAN,     // "roman" | "urdu"
    val talkMode: String = TALK_HOLD,        // "hold" | "tap"
    val demoMode: Boolean = false,           // force FULL guidance
    val debugBoxes: Boolean = false,
    val onboarded: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "baseUrl" to baseUrl,
        "appKey" to appKey,
        "voiceIn" to voiceIn,
        "voiceOut" to voiceOut,
        "caption" to caption,
        "talkMode" to talkMode,
        "demoMode" to demoMode,
        "debugBoxes" to debugBoxes,
        "onboarded" to onboarded,
    )

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        const val DEFAULT_APP_KEY = "change-me"
        const val VOICE_DEVICE = "device"
        const val VOICE_CLOUD = "cloud"
        const val CAPTION_ROMAN = "roman"
        const val CAPTION_URDU = "urdu"
        const val TALK_HOLD = "hold"
        const val TALK_TAP = "tap"

        private const val PREFS = "rehnuma_config"

        fun load(context: Context): AppConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = AppConfig()
            return AppConfig(
                baseUrl = p.getString("baseUrl", d.baseUrl) ?: d.baseUrl,
                appKey = p.getString("appKey", d.appKey) ?: d.appKey,
                voiceIn = p.getString("voiceIn", d.voiceIn) ?: d.voiceIn,
                voiceOut = p.getString("voiceOut", d.voiceOut) ?: d.voiceOut,
                caption = p.getString("caption", d.caption) ?: d.caption,
                talkMode = p.getString("talkMode", d.talkMode) ?: d.talkMode,
                demoMode = p.getBoolean("demoMode", d.demoMode),
                debugBoxes = p.getBoolean("debugBoxes", d.debugBoxes),
                onboarded = p.getBoolean("onboarded", d.onboarded),
            )
        }

        /** Merges the known keys of [values] into the stored config and returns the result. */
        fun update(context: Context, values: Map<*, *>): AppConfig {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for ((rawKey, v) in values) {
                val k = rawKey as? String ?: continue
                when (k) {
                    "baseUrl" -> (v as? String)?.let { e.putString(k, it.trim().trimEnd('/')) }
                    "appKey" -> (v as? String)?.let { e.putString(k, it.trim()) }
                    "voiceIn", "voiceOut" ->
                        (v as? String)?.takeIf { it == VOICE_DEVICE || it == VOICE_CLOUD }?.let { e.putString(k, it) }
                    "caption" ->
                        (v as? String)?.takeIf { it == CAPTION_ROMAN || it == CAPTION_URDU }?.let { e.putString(k, it) }
                    "talkMode" ->
                        (v as? String)?.takeIf { it == TALK_HOLD || it == TALK_TAP }?.let { e.putString(k, it) }
                    "demoMode", "debugBoxes", "onboarded" -> (v as? Boolean)?.let { e.putBoolean(k, it) }
                }
            }
            e.apply()
            return load(context)
        }
    }
}
