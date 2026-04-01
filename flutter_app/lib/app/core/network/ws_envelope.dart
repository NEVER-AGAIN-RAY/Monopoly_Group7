/// WebSocket 通用消息包结构。
class WsEnvelope {
  /// 业务消息类型，例如 AUTH / STATE_UPDATE。
  final String type;

  /// 任意 JSON payload。
  final Map<String, dynamic> payload;

  const WsEnvelope({required this.type, this.payload = const <String, dynamic>{}});

  factory WsEnvelope.fromJson(Map<String, dynamic> json) {
    return WsEnvelope(
      type: (json['type'] ?? '').toString(),
      payload: (json['payload'] is Map<String, dynamic>)
          ? json['payload'] as Map<String, dynamic>
          : <String, dynamic>{},
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{'type': type, 'payload': payload};
  }
}
