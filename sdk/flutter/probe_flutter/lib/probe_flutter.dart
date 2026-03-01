/// Probe Flutter SDK — plugin-based mobile app inspector.
///
/// ## Setup
/// ```dart
/// import 'package:probe_flutter/probe_flutter.dart';
///
/// final networkPlugin = NetworkPlugin();
///
/// await Probe.install(
///   ProbeConfig(
///     serverUrl: 'ws://localhost:8484',
///     plugins: [networkPlugin],
///   ),
/// );
///
/// // Wrap your http.Client or Dio interceptor:
/// final client = networkPlugin.wrapClient(http.Client());
/// ```
///
/// - Note: Flutter implementation is a placeholder. Contributions welcome.
library probe_flutter;

export 'src/probe.dart';
export 'src/probe_plugin.dart';
export 'src/network/network_plugin.dart';
