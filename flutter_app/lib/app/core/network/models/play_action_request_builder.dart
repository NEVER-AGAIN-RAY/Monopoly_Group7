import 'play_action_request.dart';

/// PLAY 请求构造器，只做参数拼装与最小校验。
class PlayActionRequestBuilder {
  const PlayActionRequestBuilder();

  PlayActionRequest buildDeposit(String cardId) {
    _requireNotBlank(cardId, 'cardId');
    return PlayActionRequest(actionType: 'DEPOSIT', cardId: cardId.trim());
  }

  PlayActionRequest buildDeploy(String cardId, {String? targetColorKey}) {
    _requireNotBlank(cardId, 'cardId');
    _requireOptionalNotBlank(targetColorKey, 'targetColorKey');
    return PlayActionRequest(
      actionType: 'DEPLOY',
      cardId: cardId.trim(),
      targetColorKey: targetColorKey?.trim(),
    );
  }

  PlayActionRequest buildAction(
    String cardId, {
    String? targetPlayerId,
    String? targetColorKey,
    String? targetCardId,
    String? actorCardId,
    String? targetZone,
    String? actingPlayerId,
  }) {
    _requireNotBlank(cardId, 'cardId');
    _requireOptionalNotBlank(targetPlayerId, 'targetPlayerId');
    _requireOptionalNotBlank(targetColorKey, 'targetColorKey');
    _requireOptionalNotBlank(targetCardId, 'targetCardId');
    _requireOptionalNotBlank(actorCardId, 'actorCardId');
    _requireOptionalNotBlank(targetZone, 'targetZone');
    _requireOptionalNotBlank(actingPlayerId, 'actingPlayerId');
    return PlayActionRequest(
      actionType: 'ACTION',
      cardId: cardId.trim(),
      targetPlayerId: targetPlayerId?.trim(),
      targetColorKey: targetColorKey?.trim(),
      targetCardId: targetCardId?.trim(),
      actorCardId: actorCardId?.trim(),
      targetZone: targetZone?.trim(),
      actingPlayerId: actingPlayerId?.trim(),
    );
  }

  PlayActionRequest buildDiscard(String cardId) {
    _requireNotBlank(cardId, 'cardId');
    return PlayActionRequest(actionType: 'DISCARD', cardId: cardId.trim());
  }

  void _requireNotBlank(String value, String fieldName) {
    if (value.trim().isEmpty) {
      throw ArgumentError.value(value, fieldName, '$fieldName cannot be blank.');
    }
  }

  void _requireOptionalNotBlank(String? value, String fieldName) {
    if (value != null && value.trim().isEmpty) {
      throw ArgumentError.value(value, fieldName, '$fieldName cannot be blank.');
    }
  }
}
