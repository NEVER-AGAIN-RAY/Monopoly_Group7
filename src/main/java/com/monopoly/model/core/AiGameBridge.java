package com.monopoly.model.core;

import com.monopoly.dto.PlayActionRequest;

/**
 * Shared play entry for humans and AI; GameController implements this
 * via GameController.handlePlayActionRequest.
 */
public interface AiGameBridge {

    void submitPlayAction(PlayActionRequest request);
}
