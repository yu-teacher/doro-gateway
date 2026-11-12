package com.hunnit_beasts.doro.bff.store;

import com.hunnit_beasts.doro.bff.core.BffLoginAttempt;
import com.hunnit_beasts.doro.bff.core.BffSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 세션·로그인 시도 저장 작업. 서비스별 저장소 어댑터({@link ServiceStores})가 서비스 이름을 붙여 이 빈을 부른다
 * (어댑터는 직접 만든 객체라 트랜잭션 프록시가 걸리지 않으므로 {@code @Transactional} 은 여기에 둔다). 메서드마다 짧고 원자적이다.
 */
@Component
public class BffStorage {

    private final AuthSessionRepository sessions;
    private final LoginAttemptRepository attempts;

    public BffStorage(AuthSessionRepository sessions, LoginAttemptRepository attempts) {
        this.sessions = sessions;
        this.attempts = attempts;
    }

    // ---------------------------------------------------------------- 세션

    @Transactional(readOnly = true)
    public Optional<BffSession> findSession(String service, String sessionHash) {
        return sessions.findByServiceAndSessionHash(service, sessionHash).map(BffStorage::toSession);
    }

    @Transactional
    public void createSession(String service, BffSession s, String previousSessionHash) {
        sessions.save(new AuthSessionEntity(service, s.sessionHash(), s.userId(), s.accessTokenEnc(), s.accessExpiresAt(),
                s.refreshTokenEnc(), s.createdAt(), s.lastUsedAt(), s.expiresAt()));
        if (previousSessionHash != null) {
            // 같은 서비스의 옛 세션만 지운다(다른 서비스의 세션은 건드리지 않는다)
            sessions.deleteByServiceAndSessionHash(service, previousSessionHash);
        }
    }

    @Transactional
    public void updateTokens(String service, String hash, String accessEnc, Instant accessExpiresAt, String refreshEnc) {
        sessions.updateTokens(service, hash, accessEnc, accessExpiresAt, refreshEnc);
    }

    @Transactional
    public void touchLastUsed(String service, String hash, Instant now) {
        sessions.touchLastUsed(service, hash, now);
    }

    @Transactional
    public void deleteSession(String service, String hash) {
        sessions.deleteByServiceAndSessionHash(service, hash);
    }

    @Transactional
    public int deleteAllSessionsByUserId(UUID userId) {
        return sessions.deleteAllByUserId(userId);
    }

    @Transactional
    public int deleteExpired(Instant now) {
        return sessions.deleteExpired(now) + attempts.deleteExpired(now);
    }

    // ---------------------------------------------------------------- 로그인 시도

    @Transactional
    public void saveAttempt(String service, BffLoginAttempt a) {
        attempts.save(new LoginAttemptEntity(service, a.stateHash(), a.codeVerifierEnc(), a.returnPath(), a.browserHash(),
                a.createdAt(), a.expiresAt()));
    }

    /** state 는 한 번만: 실제로 지운 행이 1 일 때만 값을 돌려준다. 다른 서비스의 state 는 이 서비스에서 쓸 수 없다. */
    @Transactional
    public Optional<BffLoginAttempt> consumeAttempt(String service, String stateHash) {
        Optional<LoginAttemptEntity> found = attempts.findById(stateHash).filter(a -> a.getService().equals(service));
        int deleted = attempts.deleteByServiceAndStateHash(service, stateHash);
        return found.filter(a -> deleted == 1).map(a -> new BffLoginAttempt(a.getStateHash(), a.getCodeVerifierEnc(),
                a.getReturnPath(), a.getBrowserHash(), a.getCreatedAt(), a.getExpiresAt()));
    }

    private static BffSession toSession(AuthSessionEntity s) {
        return new BffSession(s.getSessionHash(), s.getUserId(), s.getAccessTokenEnc(), s.getAccessExpiresAt(),
                s.getRefreshTokenEnc(), s.getCreatedAt(), s.getLastUsedAt(), s.getExpiresAt());
    }
}
