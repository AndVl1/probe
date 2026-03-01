package dev.probe.sample

import android.app.Application
import dev.probe.Probe
import dev.probe.network.NetworkPlugin

class SampleApplication : Application() {

    companion object {
        val networkPlugin = NetworkPlugin()
    }

    override fun onCreate() {
        super.onCreate()

        // Probe server URL:
        //   - Emulator: ws://10.0.2.2:8484  (host machine from emulator)
        //   - Physical device (USB): ws://localhost:8484 + run: adb reverse tcp:8484 tcp:8484
        //   - Physical device (WiFi): ws://<your-machine-LAN-IP>:8484
        val serverUrl = if (isEmulator()) "ws://10.0.2.2:8484" else "ws://localhost:8484"
        Probe.install(
            Probe.Builder(this)
                .serverUrl(serverUrl)
                .plugin(networkPlugin)
                .build()
        )
    }

    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.startsWith("generic")
            || android.os.Build.FINGERPRINT.startsWith("unknown")
            || android.os.Build.MODEL.contains("google_sdk")
            || android.os.Build.MODEL.contains("Emulator")
            || android.os.Build.MODEL.contains("Android SDK built for x86")
            || android.os.Build.MANUFACTURER.contains("Genymotion")
            || android.os.Build.BRAND.startsWith("generic")
            || android.os.Build.DEVICE.startsWith("generic")
    }
}
