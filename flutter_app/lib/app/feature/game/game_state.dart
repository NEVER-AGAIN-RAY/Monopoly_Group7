/// 对局页状态对象，保存关键服务端推送内容。
class GameState {
  /// 来自 STATE_UPDATE 的快照。
  final Map<String, dynamic>? snapshot;

  /// 来自 MY_HAND 的手牌数据。
  final Map<String, dynamic>? myHand;

  /// 来自结果类消息的文本反馈。
  final List<String> feedback;

  const GameState({this.snapshot, this.myHand, this.feedback = const <String>[]});

  GameState copyWith({
    Map<String, dynamic>? snapshot,
    Map<String, dynamic>? myHand,
    List<String>? feedback,
  }) {
    return GameState(
      snapshot: snapshot ?? this.snapshot,
      myHand: myHand ?? this.myHand,
      feedback: feedback ?? this.feedback,
    );
  }
}
