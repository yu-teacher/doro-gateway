package com.hunnit_beasts.doro.bff.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 진행 중인 로그인(인가 요청을 보냈고 콜백을 기다리는 상태). state 해시로 찾고, 한 번 쓰면 지운다. */
@Entity
@Table(name = "login_attempts")
public class LoginAttemptEntity {

    @Id
    @Column(name = "state_hash", length = 64)
    private String stateHash;

    @Column(name = "service", nullable = false, updatable = false, length = 32)
    private String service;

    @Column(name = "code_verifier_enc", nullable = false, columnDefinition = "TEXT")
    private String codeVerifierEnc;

    @Column(name = "return_path", nullable = false, length = 500)
    private String returnPath;

    /** 로그인을 시작한 브라우저에 심은 일회용 값의 해시(로그인 CSRF 방지). */
    @Column(name = "browser_hash", length = 64)
    private String browserHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected LoginAttemptEntity() {}

    public LoginAttemptEntity(String service, String stateHash, String codeVerifierEnc, String returnPath, String browserHash,
                              Instant createdAt, Instant expiresAt) {
        this.service = service;
        this.stateHash = stateHash;
        this.codeVerifierEnc = codeVerifierEnc;
        this.returnPath = returnPath;
        this.browserHash = browserHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getStateHash() { return stateHash; }
    public String getService() { return service; }
    public String getCodeVerifierEnc() { return codeVerifierEnc; }
    public String getReturnPath() { return returnPath; }
    public String getBrowserHash() { return browserHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
