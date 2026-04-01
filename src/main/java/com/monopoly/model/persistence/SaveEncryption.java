package com.monopoly.model.persistence;

import com.monopoly.model.GameConstants;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 存档轻量加密（§2.2.3）：AES-256-GCM。密钥由 JVM 属性 {@link GameConstants#SAVE_KEY_PROPERTY} 提供；
 * 未设置时 {@link #encodeForStorage(String)} / {@link #decodeFromStorage(String)} 保持明文。
 */
public final class SaveEncryption {

    private static final String ENC_PREFIX = "ENC1:";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private SaveEncryption() {
    }

    /** @return 已 trim 的密钥，未配置则 {@code null} */
    public static String getKeyOrNull() {
        String k = System.getProperty(GameConstants.SAVE_KEY_PROPERTY);
        if (k == null || k.isBlank()) {
            return null;
        }
        return k.trim();
    }

    /**
     * AES-GCM：密文为 {@code IV(12) || ciphertext}。
     */
    public static byte[] encrypt(String plain, String key) {
        if (plain == null) {
            throw new IllegalArgumentException("plain 不能为 null");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("加密需要非空 key");
        }
        try {
            SecretKeySpec sk = aesKeyFromPassphrase(key);
            byte[] iv = new byte[GCM_IV_LEN];
            SecureRandom rnd = new SecureRandom();
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    public static String decrypt(byte[] cipher, String key) {
        if (cipher == null || cipher.length <= GCM_IV_LEN) {
            throw new IllegalArgumentException("密文无效");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("解密需要非空 key");
        }
        try {
            byte[] iv = Arrays.copyOfRange(cipher, 0, GCM_IV_LEN);
            byte[] ct = Arrays.copyOfRange(cipher, GCM_IV_LEN, cipher.length);
            SecretKeySpec sk = aesKeyFromPassphrase(key);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = c.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("解密失败：密钥错误或数据已损坏。", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 解密失败", e);
        }
    }

    /**
     * 写盘用：有密钥则 {@code ENC1:} + Base64(encrypt)，否则原文。
     */
    public static String encodeForStorage(String plainJson) {
        String k = getKeyOrNull();
        if (k == null) {
            return plainJson;
        }
        byte[] raw = encrypt(plainJson, k);
        return ENC_PREFIX + Base64.getEncoder().encodeToString(raw);
    }

    /**
     * 读盘 / LOAD：无密钥或内容为明文 JSON（以 {@code '{' } 开头）则原样返回；否则解密。
     */
    public static String decodeFromStorage(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("内容不能为 null");
        }
        String t = stored.trim();
        String k = getKeyOrNull();
        if (k == null) {
            return t;
        }
        if (t.startsWith("{")) {
            return t;
        }
        if (!t.startsWith(ENC_PREFIX)) {
            return t;
        }
        String b64 = t.substring(ENC_PREFIX.length()).trim();
        byte[] raw = Base64.getDecoder().decode(b64);
        return decrypt(raw, k);
    }

    private static SecretKeySpec aesKeyFromPassphrase(String passphrase) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(passphrase.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
