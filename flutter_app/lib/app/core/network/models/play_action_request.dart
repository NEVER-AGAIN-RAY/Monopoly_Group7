/// PLAY 请求模型。
class PlayActionRequest {
  final String actionType;
  final String cardId;
  final String? targetPlayerId;
  final String? targetColorKey;
  final String? targetCardId;
  final String? actorCardId;
  final String? targetZone;
  final String? actingPlayerId;

  const PlayActionRequest({
    required this.actionType,
    required this.cardId,
    this.targetPlayerId,
    this.targetColorKey,
    this.targetCardId,
    this.actorCardId,
    this.targetZone,
    this.actingPlayerId,
  });

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'actionType': actionType,
      'cardId': cardId,
      if (targetPlayerId != null) 'targetPlayerId': targetPlayerId,
      if (targetColorKey != null) 'targetColorKey': targetColorKey,
      if (targetCardId != null) 'targetCardId': targetCardId,
      if (actorCardId != null) 'actorCardId': actorCardId,
      if (targetZone != null) 'targetZone': targetZone,
      if (actingPlayerId != null) 'actingPlayerId': actingPlayerId,
    };
  }
}
