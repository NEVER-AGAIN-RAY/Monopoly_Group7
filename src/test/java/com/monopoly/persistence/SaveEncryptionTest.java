package com.monopoly.persistence;

import com.monopoly.controller.GameController;
import com.monopoly.model.core.GameConstants;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveEncryptionTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(GameConstants.SAVE_KEY_PROPERTY);
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void aesGcm_roundTrip() {
        String key = "course-demo-key";
        String plain = "{\"sessionId\":\"x\",\"n\":1}";
        byte[] c = SaveEncryption.encrypt(plain, key);
        assertTrue(c.length > 12);
        assertEquals(plain, SaveEncryption.decrypt(c, key));
    }

    @Test
    void encodeForStorage_withoutProperty_isPlain() {
        assertEquals("{\"a\":1}", SaveEncryption.encodeForStorage("{\"a\":1}"));
    }

    @Test
    void encodeDecode_withSaveKey_roundTrip() {
        System.setProperty(GameConstants.SAVE_KEY_PROPERTY, "k1");
        String plain = "{\"hello\":\"world\"}";
        String stored = SaveEncryption.encodeForStorage(plain);
        assertTrue(stored.startsWith("ENC1:"));
        assertEquals(plain, SaveEncryption.decodeFromStorage(stored));
    }

    @Test
    void importSessionJson_loadsEncryptedExport() {
        System.setProperty(GameConstants.SAVE_KEY_PROPERTY, "import-key");
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController c1 = new GameController(subject);
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("enc-load");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        c1.startNewSession(req);

        String enc = SaveEncryption.encodeForStorage(c1.exportSessionJson());

        GameSessionMemento.resetSingletonEngineForTests();
        GameController c2 = new GameController(subject);
        c2.importSessionJson(enc);

        assertEquals(2, c2.getSessionPlayersView().size());
        assertEquals("enc-load", GameSessionMemento.fromJson(c2.exportSessionJson()).getSessionId());
    }
}
