package com.hunnit_beasts.doro.bff.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 저장소에 두는 토큰과 PKCE verifier 를 AES-256-GCM 으로 암호화한다. 형식: {@code v1:} + base64(IV 12바이트 + 암호문+태그).
 * 키는 설정(환경변수)으로만 받고 코드와 저장소에 두지 않는다.
 */
public class BffCrypto {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public BffCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("BFF 세션 암호화 키(session-encryption-key)가 필요하다. 생성: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("session-encryption-key 는 base64 여야 한다", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("session-encryption-key 는 32바이트(base64)여야 한다 (현재 " + raw.length + "바이트). "
                    + "생성: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화에 실패했습니다", e);
        }
    }

    /** @throws IllegalStateException 형식이 다르거나 키가 맞지 않거나 변조된 경우 */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("알 수 없는 암호문 형식");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("암호문이 너무 짧다");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("복호화에 실패했습니다 (키가 바뀌었거나 데이터가 변조됨)", e);
        }
    }
}
