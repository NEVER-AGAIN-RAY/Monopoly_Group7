import '../../core/network/ws_client.dart';
import '../../core/network/models/start_session_request.dart';

/// 大厅页状态与命令，负责基础连接和会话参数。
class LobbyViewModel {
  final WsClient _wsClient;

  String wsUrl = 'ws://localhost:8025/ws';
  String playerId = 'pvp-1';
  String sessionId = 'demo-pvp';
  int playerCount = 2;
  String gameMode = 'PVP';
  bool randomizeFirstPlayer = false;

  LobbyViewModel({required WsClient wsClient}) : _wsClient = wsClient;

  Future<void> connect() {
    return _wsClient.connect(wsUrl.trim());
  }

  void sendAuth() {
    _wsClient.send('AUTH', <String, dynamic>{'playerId': playerId.trim()});
  }

  void sendStartSession() {
    final StartSessionRequest request = StartSessionRequest(
      sessionId: sessionId.trim(),
      playerCount: playerCount,
      gameMode: gameMode.trim(),
      randomizeFirstPlayer: randomizeFirstPlayer,
    );
    _wsClient.send('START_SESSION', request.toJson());
  }
}
