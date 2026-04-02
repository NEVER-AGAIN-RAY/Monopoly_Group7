package com.monopoly.controller;

import com.monopoly.model.player.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 暂停与 PVP 投票逻辑，从 {@link GameController} 抽出。
 * <p>
 * 持有暂停相关可变状态（{@code paused}、{@code pausePending}、{@code pauseAcks}）；
 * 通过回调 {@link GameController} 包级方法完成快照推送与错误记录，避免双写。
 */
final class PauseVoteService {

    static final String MSG_PAUSED = "游戏已暂停。";

    private volatile boolean paused;
    private volatile boolean pausePending;
    private final Set<String> pauseAcks = new HashSet<>();

    private final GameController controller;

    PauseVoteService(GameController controller) {
        this.controller = controller;
    }

    boolean isPaused() {
        return paused;
    }

    boolean isPausePending() {
        return pausePending;
    }

    Set<String> getPauseAcksView() {
        return Collections.unmodifiableSet(new HashSet<>(pauseAcks));
    }

    /**
     * 人机模式立即暂停；PVP 模式抛错。
     */
    void pause() {
        if (controller.isPvpMode()) {
            throw new IllegalStateException("人人模式请使用 PAUSE_REQUEST / PAUSE_ACK。");
        }
        pauseImmediate();
    }

    /**
     * PVP: 当前回合玩家发起暂停请求。HVM: 等价于立即暂停。
     */
    void requestPause() {
        if (paused) {
            return;
        }
        if (!controller.isPvpMode()) {
            pauseImmediate();
            return;
        }
        Player cur = controller.requireCurrentPlayer();
        String turnPid = controller.getCurrentTurnPlayerId();
        if (cur == null || turnPid == null || !turnPid.equals(cur.getPlayerId())) {
            throw new IllegalStateException("仅当前回合玩家可发起 PAUSE_REQUEST。");
        }
        if (pausePending) {
            return;
        }
        pausePending = true;
        pauseAcks.clear();
        controller.pushSnapshot(controller.getCurrentSessionId(), "PAUSE_PENDING");
    }

    /**
     * PVP: 玩家确认暂停；全员确认后暂停生效。
     */
    void acknowledgePause(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空。");
        }
        if (!controller.isPvpMode()) {
            return;
        }
        if (paused || !pausePending) {
            return;
        }
        String pid = playerId.trim();
        boolean known = false;
        for (Player p : controller.getSessionPlayersView()) {
            if (p.getPlayerId().equals(pid)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new IllegalArgumentException("未知玩家: " + pid);
        }
        pauseAcks.add(pid);
        if (pauseAcks.size() >= controller.getSessionPlayersView().size()) {
            paused = true;
            pausePending = false;
            pauseAcks.clear();
            controller.recordError("PAUSED", MSG_PAUSED);
            controller.pushSnapshot(controller.getCurrentSessionId(), "RULE_VIOLATION");
        } else {
            controller.pushSnapshot(controller.getCurrentSessionId(), "PAUSE_PENDING");
        }
    }

    void resume() {
        if (!paused && !pausePending) {
            return;
        }
        paused = false;
        pausePending = false;
        pauseAcks.clear();
        controller.clearLastError();
        controller.pushSnapshot(controller.getCurrentSessionId(), "INIT");
    }

    void ensureNotPaused() {
        if (!paused) {
            return;
        }
        controller.recordError("PAUSED", MSG_PAUSED);
        controller.pushSnapshot(controller.getCurrentSessionId(), "RULE_VIOLATION");
        throw new IllegalStateException(MSG_PAUSED);
    }

    /** 会话启动 / 存档恢复时重置暂停状态。 */
    void reset() {
        paused = false;
        pausePending = false;
        pauseAcks.clear();
    }

    private void pauseImmediate() {
        if (paused) {
            return;
        }
        paused = true;
        pausePending = false;
        pauseAcks.clear();
        controller.recordError("PAUSED", MSG_PAUSED);
        controller.pushSnapshot(controller.getCurrentSessionId(), "RULE_VIOLATION");
    }
}
