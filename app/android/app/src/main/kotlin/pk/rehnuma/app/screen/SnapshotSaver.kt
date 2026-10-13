package pk.rehnuma.app.screen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import pk.rehnuma.app.net.ApiJson
import pk.rehnuma.app.net.DebugSnapshotFile
import pk.rehnuma.app.net.toApi
import java.io.File

/**
 * "Save snapshot" from the debug menu (SPEC 11). The only place screen content is written to disk,
 * and only on an explicit user action (rule 5). Sensitive screens are refused (rule 3).
 */
object SnapshotSaver {

    sealed class Result {
        data class Saved(val file: File) : Result()
        data object Sensitive : Result()
    }

    fun save(context: Context, snapshot: ScreenSnapshot): Result {
        if (SafetyGuard.check(snapshot) is GuardResult.Sensitive) return Result.Sensitive
        val dir = File(context.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
        val file = File(dir, "${snapshot.capturedAtMs}.json")
        val body = DebugSnapshotFile(
            screen = snapshot.toApi(),
            capturedAtMs = snapshot.capturedAtMs,
            readMs = snapshot.readMs,
        )
        file.writeText(PrettyJson.encodeToString(DebugSnapshotFile.serializer(), body))
        return Result.Saved(file)
    }

    /** Opens the system share sheet for [file] (started from a service, so NEW_TASK). */
    fun share(context: Context, file: File, mime: String = "application/json") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, file.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    /** Keeps empty `goal_text` and `expected` in the file so the team can fill them in. */
    private val PrettyJson = kotlinx.serialization.json.Json(from = ApiJson) {
        prettyPrint = true
        encodeDefaults = true
    }
}
