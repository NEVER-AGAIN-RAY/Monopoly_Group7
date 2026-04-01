import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/network/models/play_action_request.dart';
import '../../core/network/models/play_action_request_builder.dart';
import '../../core/network/ws_client.dart';
import '../../core/network/ws_envelope.dart';
import 'game_state.dart';

/// 对局页 ViewModel，负责消息订阅与分发。
class GameViewModel extends ChangeNotifier {
  final WsClient _wsClient;
  final PlayActionRequestBuilder _playBuilder = const PlayActionRequestBuilder();
  StreamSubscription<WsEnvelope>? _subscription;

  GameState _state = const GameState();

  GameViewModel({required WsClient wsClient}) : _wsClient = wsClient;

  /// 当前页面状态快照。
  GameState get state => _state;

  /// 发送 DRAW 请求。
  void draw() {
    _wsClient.send('DRAW', <String, dynamic>{'count': 2});
  }

  /// 发送 END_TURN 请求。
  void endTurn() {
    _wsClient.send('END_TURN', <String, dynamic>{});
  }

  /// 发送示例 PLAY(DEP0) 请求。
  void playDepositExample() {
    final PlayActionRequest request = _playBuilder.buildDeposit('demo-card-id');
    _wsClient.send('PLAY', request.toJson());
  }

  /// 开始监听 WsClient 消息流。
  void start() {
    _subscription ??= _wsClient.messages.listen(_dispatchMessage);
  }

  /// 停止监听，释放订阅。
  Future<void> stop() async {
    await _subscription?.cancel();
    _subscription = null;
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _subscription = null;
    super.dispose();
  }

  void _dispatchMessage(WsEnvelope envelope) {
    switch (envelope.type) {
      case 'STATE_UPDATE':
        _state = _state.copyWith(snapshot: envelope.payload);
        notifyListeners();
        break;
      case 'MY_HAND':
        _state = _state.copyWith(myHand: envelope.payload);
        notifyListeners();
        break;
      case 'AUTH_RESULT':
      case 'SAVE_GAME_RESULT':
      case 'LOAD_GAME_RESULT':
        final List<String> logs = List<String>.from(_state.feedback);
        logs.insert(0, '${envelope.type}: ${envelope.payload}');
        _state = _state.copyWith(feedback: logs.take(20).toList());
        notifyListeners();
        break;
      default:
        break;
    }
  }
}
