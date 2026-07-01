package tech.devlens.sample

import android.app.Application
import android.content.Context
import tech.devlens.Probe
import tech.devlens.db.DatabasePlugin
import tech.devlens.network.NetworkPlugin
import tech.devlens.prefs.PreferencesPlugin
import tech.devlens.sample.data.SampleDatabase

class SampleApplication : Application() {

    companion object {
        val networkPlugin = NetworkPlugin()

        // PreferencesPlugin needs an Application Context, which the companion
        // object does not have at init time. Assigned in onCreate (which runs
        // before any Activity / ViewModel is created) UNCONDITIONALLY — including
        // release builds — so MainViewModel.registerLatePrefs() can read it
        // without an UninitializedPropertyAccessException. The plugin is inert
        // until Probe.install() attaches it (DEBUG only).
        lateinit var prefsPlugin: PreferencesPlugin
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Construct PreferencesPlugin unconditionally (also in release builds).
        // It only stores the application context and sets a few fields — no I/O,
        // no listeners, no side effects until Probe.install() attaches it. Keeping
        // this out of the DEBUG guard means MainViewModel.registerLatePrefs() can
        // read `prefsPlugin` without an `UninitializedPropertyAccessException` in
        // a release build (where the lateinit would otherwise never be assigned).
        // Probe.install() itself stays DEBUG-guarded below so DevLens never runs
        // in a shipped release.
        prefsPlugin = PreferencesPlugin(this)

        if (BuildConfig.DEBUG) {
            // Touch the sample database so its file exists for the DatabasePlugin
            // to inspect on a fresh install (SQLiteOpenHelper creates it on first open).
            SampleDatabase(this).readableDatabase.use { /* force create + seed */ }

            // Seed probe_demo so the PreferencesPlugin snapshot has content on a
            // fresh install (theme / launchCount / notificationsEnabled). launchCount
            // increments on every launch so the change event path is exercised too.
            getSharedPreferences("probe_demo", Context.MODE_PRIVATE).let { prefs ->
                val count = prefs.getInt("launchCount", 0) + 1
                prefs.edit()
                    .putString("theme", "dark")
                    .putInt("launchCount", count)
                    .putBoolean("notificationsEnabled", true)
                    .apply()
            }

            // Probe server URL:
            //   - Emulator: ws://10.0.2.2:8484  (host machine from emulator)
            //   - Physical device (USB): ws://localhost:8484 + run: adb reverse tcp:8484 tcp:8484
            //   - Physical device (WiFi): ws://<your-machine-LAN-IP>:8484
            val serverUrl = if (isEmulator()) "ws://10.0.2.2:8484" else "ws://localhost:8484"
            Probe.install(
                Probe.Builder(this)
                    .serverUrl(serverUrl)
                    .plugin(networkPlugin)
                    .plugin(DatabasePlugin(this))
                    .plugin(prefsPlugin)
                    .build()
            )
        }
    }

    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.startsWith("generic")
            || android.os.Build.FINGERPRINT.startsWith("unknown")
            || android.os.Build.FINGERPRINT.contains("sdk_gphone")
            || android.os.Build.MODEL.contains("google_sdk")
            || android.os.Build.MODEL.contains("Emulator")
            || android.os.Build.MODEL.contains("Android SDK built for x86")
            || android.os.Build.MODEL.contains("gphone")
            || android.os.Build.MANUFACTURER.contains("Genymotion")
            || android.os.Build.BRAND.startsWith("generic")
            || android.os.Build.DEVICE.startsWith("generic")
            || android.os.Build.PRODUCT.contains("sdk")
            || android.os.Build.PRODUCT.contains("emulator")
            || android.os.Build.HARDWARE.contains("ranchu")
            || java.io.File("/dev/qemu_pipe").exists()
    }
}
