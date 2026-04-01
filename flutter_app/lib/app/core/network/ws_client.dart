import 'dart:async';

import 'package:web_socket_channel/web_socket_channel.dart';

import 'ws_envelope.dart';
import 'ws_message_codec.dart';
import 'ws_traffic_message.dart';

/// 轻量 WebSocket 客户端，封装连接与消息流。
class WsClient {
  final WsMessageCodec _codec;
  final StreamController<WsEnvelope> _messageController =
      StreamController<WsEnvelope>.broadcast();
  final StreamController<WsTrafficMessage> _trafficController =
      StreamController<WsTrafficMessage>.broadcast();

  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;

  WsClient({WsMessageCodec? codec}) : _codec = codec ?? const WsMessageCodec();

  /// 对外暴露已解码的消息流。
  Stream<WsEnvelope> get messages => _messageController.stream;
  /// 对外暴露收发日志流。
  Stream<WsTrafficMessage> get traffic => _trafficController.stream;

  /// 建立连接；如已有连接会先释放旧连接。
  Future<void> connect(String url) async {
    await _closeConnection();
    final WebSocketChannel channel = WebSocketChannel.connect(Uri.parse(url));
    _channel = channel;
    _subscription = channel.stream.listen(_onRawMessage, onDone: _clearChannel, onError: (_) {
      _clearChannel();
    });
  }

  /// 统一发送入口：按 type 和 payload 发送。
  void send(String type, Map<String, dynamic> payload) {
    sendEnvelope(WsEnvelope(type: type, payload: payload));
  }

  /// 发送业务消息包。
  void sendEnvelope(WsEnvelope envelope) {
    final WebSocketChannel? channel = _channel;
    if (channel == null) {
      throw StateError('WebSocket is not connected.');
    }
    final String raw = _codec.encode(envelope);
    channel.sink.add(raw);
    _trafficController.add(
      WsTrafficMessage(
        direction: WsTrafficDirection.outbound,
        type: envelope.type,
        payload: envelope.payload,
      ),
    );
  }

  /// 关闭连接并清理内部资源。
  Future<void> dispose() async {
    await _closeConnection();
    await _messageController.close();
    await _trafficController.close();
  }

  Future<void> _closeConnection() async {
    await _subscription?.cancel();
    _subscription = null;
    await _channel?.sink.close();
    _channel = null;
  }

  void _onRawMessage(dynamic data) {
    final String raw = data.toString();
    try {
      final WsEnvelope envelope = _codec.decode(raw);
      _messageController.add(envelope);
      _trafficController.add(
        WsTrafficMessage(
          direction: WsTrafficDirection.inbound,
          type: envelope.type,
          payload: envelope.payload,
        ),
      );
    } catch (_) {
      // 最小骨架阶段忽略无法解析的消息。
    }
  }

  void _clearChannel() {
    _subscription = null;
    _channel = null;
  }
}
