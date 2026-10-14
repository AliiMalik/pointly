package pk.pointly.app.screen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import pk.pointly.app.net.ApiJson
import pk.pointly.app.net.DebugSnapshotFile
import pk.pointly.app.net.toApi
import java.io.File

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

    private val PrettyJson = kotlinx.serialization.json.Json(from = ApiJson) {
        prettyPrint = true
        encodeDefaults = true
    }
}
