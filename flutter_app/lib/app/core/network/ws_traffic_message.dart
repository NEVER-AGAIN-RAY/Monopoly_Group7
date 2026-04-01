/// WebSocket 收发方向。
enum WsTrafficDirection { inbound, outbound }

/// WebSocket 收发日志条目。
class WsTrafficMessage {
  final WsTrafficDirection direction;
  final String type;
  final Map<String, dynamic> payload;

  const WsTrafficMessage({
    required this.direction,
    required this.type,
    required this.payload,
  });
}
