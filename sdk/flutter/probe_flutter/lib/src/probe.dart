import 'probe_plugin.dart';

/// Configuration for Probe.
class ProbeConfig {
  final String serverUrl;
  final List<ProbePlugin> plugins;

  const ProbeConfig({
    required this.serverUrl,
    this.plugins = const [],
  });
}

/// Probe — plugin-based mobile app inspector for Flutter.
///
/// - Note: Flutter implementation is a placeholder. Contributions welcome.
class Probe {
  static Probe? _instance;

  static Future<void> install(ProbeConfig config) async {
    // TODO: implement WebSocket transport + plugin lifecycle
  }

  static void uninstall() {
    _instance = null;
  }
}
