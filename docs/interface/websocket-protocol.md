# WebSocket Protocol

This document defines the JSON message protocol between client and server.

## Envelope

All messages use a shared envelope:

```json
{
  "type": "MESSAGE_TYPE",
  "payload": {}
}
```

## Client -> Server Messages

Implemented message types:

- `AUTH`
- `JOIN_SESSION`
- `START_SESSION`
- `PAUSE`
- `PAUSE_REQUEST`
- `PAUSE_ACK`
- `RESUME`
- `REASSIGN_WILD`
- `DRAW`
- `PLAY`
- `END_TURN`
- `QUIT`
- `RESPONSE_PASS`
- `SAVE_GAME`
- `LOAD_GAME`
- `LOAD_VOTE`
- `PING`

Minimum required flow coverage:

- `AUTH`
- `START_SESSION`
- `DRAW`
- `PLAY`
- `END_TURN`
- `SAVE_GAME`
- `LOAD_GAME`

## Server -> Client Messages (ACK/REJECT-style included)

- `STATE_UPDATE` (broadcast state snapshot)
- `MY_HAND` (private hand payload)
- `AUTH_RESULT` (`ok: true/false`, includes error on failure)
- `SAVE_GAME_RESULT` (`ok: true/false`, optional `error`)
- `LOAD_GAME_RESULT` (`ok: true/false`, optional `error`)
- `LOAD_VOTE_REQUIRED` (vote required notification)
- `LOAD_VOTE_PROGRESS` (vote progress updates)

Note: This project uses `*_RESULT` with `ok/error` fields as ACK/REJECT style responses.

## Compatibility and Deprecation Notes

- `PAUSE` and `PAUSE_REQUEST` may both appear in clients. `PAUSE_REQUEST` is the explicit multi-player vote-oriented request.
- `JOIN_SESSION` and `AUTH` are both used for identity/session binding workflows depending on client stage.
- If legacy docs mention non-result ACK labels, treat `*_RESULT` + `ok/error` as the canonical replacement.

## Field Notes

- `SAVE_GAME.payload.path` is optional. If absent, server can return serialized save content in response.
- `LOAD_GAME.payload.mementoJson` is required for load operations.
- `PLAY.payload` maps to a play action request body; fields vary by action type.

## Maintenance Rule

When message type branches change in server message handling logic, this document must be updated in the same change set.
