package com.monopoly.controller;

import com.monopoly.model.core.GameConstants;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.persistence.SaveEncryption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 存档 / 读档 / 自动保存，从 {@link GameController} 抽出。
 * <p>
 * 仅做数据序列化 / 反序列化 / 文件 IO；恢复后对控制器状态的重置
 * 通过 {@link GameController#resetStateAfterLoad(String)} 回调完成，避免双写。
 */
final class SaveLoadService {

    private static final Logger LOG = Logger.getLogger(SaveLoadService.class.getName());

    private final GameController controller;

    SaveLoadService(GameController controller) {
        this.controller = controller;
    }

    String exportSessionJson() {
        return GameSessionMemento.capture(controller).toJson();
    }

    void importSessionJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("memento JSON 不能为空。");
        }
        String plain = SaveEncryption.decodeFromStorage(json);
        GameSessionMemento m = GameSessionMemento.fromJson(plain);
        GameSessionMemento.applyToController(controller, m);
        controller.resetStateAfterLoad(m.getGameMode());
    }

    void maybeAutosaveAfterFullRound(int fullRoundsCompleted) {
        if (fullRoundsCompleted <= 0 || fullRoundsCompleted % 3 != 0) {
            return;
        }
        Path path = Path.of(System.getProperty("user.home"), ".monopoly-deal", "autosave.json");
        LOG.info("autosave eligible: fullRoundsCompleted=" + fullRoundsCompleted + " path=" + path);
        if (!Boolean.parseBoolean(System.getProperty(GameConstants.AUTOSAVE_PROPERTY, "false"))) {
            return;
        }
        try {
            String json = exportSessionJson();
            String payload = SaveEncryption.encodeForStorage(json);
            Files.createDirectories(path.getParent());
            Files.writeString(path, payload, StandardCharsets.UTF_8);
            LOG.info("autosave written chars=" + payload.length() + " path=" + path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "autosave failed: " + path, e);
        }
    }
}
