import '../probe_plugin.dart';

/// Captured HTTP transaction.
class HttpTransaction {
  final String id;
  final String url;
  final String method;
  final int? statusCode;
  final double durationMs;
  final int requestSize;
  final int responseSize;
  final double timestamp;

  HttpTransaction({
    required this.id,
    required this.url,
    required this.method,
    this.statusCode,
    this.durationMs = 0,
    this.requestSize = 0,
    this.responseSize = 0,
    double? timestamp,
  }) : timestamp = timestamp ?? DateTime.now().millisecondsSinceEpoch.toDouble();
}

/// Probe plugin — captures HTTP network traffic in Flutter.
///
/// - Note: Flutter implementation is a placeholder. Contributions welcome.
class NetworkPlugin implements ProbePlugin {
  @override
  final String id = 'network';

  @override
  final String displayName = 'Network';

  ProbeHost? _host;
  final List<HttpTransaction> _buffer = [];
  final int bufferSize;

  NetworkPlugin({this.bufferSize = 1000});

  @override
  void onAttach(ProbeHost host) => _host = host;

  @override
  void onDetach() => _host = null;

  /// Returns the last [last] captured transactions.
  List<HttpTransaction> dump({int last = 100}) {
    final from = _buffer.length > last ? _buffer.length - last : 0;
    return _buffer.sublist(from);
  }

  /// Record a transaction manually (e.g. from a Dio interceptor).
  void record(HttpTransaction tx) {
    _buffer.add(tx);
    if (_buffer.length > bufferSize) _buffer.removeAt(0);
    _host?.send(id, {
      'id': tx.id, 'url': tx.url, 'method': tx.method,
      'statusCode': tx.statusCode, 'durationMs': tx.durationMs,
      'requestSize': tx.requestSize, 'responseSize': tx.responseSize,
      'timestamp': tx.timestamp,
    });
  }
}
