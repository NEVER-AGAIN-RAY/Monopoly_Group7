/// START_SESSION 请求模型。
class StartSessionRequest {
  final String sessionId;
  final int playerCount;
  final String gameMode;
  final bool randomizeFirstPlayer;

  const StartSessionRequest({
    required this.sessionId,
    required this.playerCount,
    required this.gameMode,
    required this.randomizeFirstPlayer,
  });

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'sessionId': sessionId,
      'playerCount': playerCount,
      'gameMode': gameMode,
      'randomizeFirstPlayer': randomizeFirstPlayer,
    };
  }
}
