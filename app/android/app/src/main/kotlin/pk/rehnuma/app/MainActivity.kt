package pk.rehnuma.app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import pk.rehnuma.app.bridge.RehnumaChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        RehnumaChannel(this).register(flutterEngine)
    }
}
