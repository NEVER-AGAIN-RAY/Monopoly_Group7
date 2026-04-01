import 'dart:convert';

import 'ws_envelope.dart';

/// 负责 WsEnvelope 与字符串消息互转。
class WsMessageCodec {
  const WsMessageCodec();

  String encode(WsEnvelope envelope) {
    return jsonEncode(envelope.toJson());
  }

  WsEnvelope decode(String raw) {
    final dynamic decoded = jsonDecode(raw);
    if (decoded is Map<String, dynamic>) {
      return WsEnvelope.fromJson(decoded);
    }
    throw const FormatException('WebSocket message must be a JSON object.');
  }
}
