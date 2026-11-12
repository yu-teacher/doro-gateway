package com.hunnit_beasts.doro.bff.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** BFF 로그인 세션. 쿠키에는 세션 식별자 원문만 있고 여기에는 그 해시와 암호화된 토큰이 있다. service 는 어느 서비스의 세션인지다. */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

    @Id
    private UUID id;

    @Column(name = "service", nullable = false, updatable = false, length = 32)
    private String service;

    @Column(name = "session_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String sessionHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "access_token_enc", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(name = "access_expires_at", nullable = false)
    private Instant accessExpiresAt;

    @Column(name = "refresh_token_enc", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenEnc;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected AuthSessionEntity() {}

    public AuthSessionEntity(String service, String sessionHash, UUID userId, String accessTokenEnc, Instant accessExpiresAt,
                             String refreshTokenEnc, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.service = service;
        this.sessionHash = sessionHash;
        this.userId = userId;
        this.accessTokenEnc = accessTokenEnc;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshTokenEnc = refreshTokenEnc;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.expiresAt = expiresAt;
    }

    public String getService() { return service; }
    public String getSessionHash() { return sessionHash; }
    public UUID getUserId() { return userId; }
    public String getAccessTokenEnc() { return accessTokenEnc; }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public String getRefreshTokenEnc() { return refreshTokenEnc; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
