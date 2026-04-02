package com.monopoly.model.core;

import com.monopoly.dto.PlayActionRequest;

/**
 * AI 与真实玩家共用的出牌入口抽象：由 {@link com.monopoly.controller.GameController} 实现并转交
 * {@link com.monopoly.controller.GameController#handlePlayActionRequest(PlayActionRequest)}，
 * 保证人机走同一套校验与效果逻辑。
 */
public interface AiGameBridge {

    void submitPlayAction(PlayActionRequest request);
}
