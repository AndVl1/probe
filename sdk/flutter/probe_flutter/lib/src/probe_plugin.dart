/// A Probe plugin provides a specific inspection capability.
abstract class ProbePlugin {
  String get id;
  String get displayName;

  void onAttach(ProbeHost host);
  void onDetach();
}

/// The host provides transport access to plugins.
abstract class ProbeHost {
  bool get isConnected;
  void send(String pluginId, Map<String, dynamic> payload);
}
