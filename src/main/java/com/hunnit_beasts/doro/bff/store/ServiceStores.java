package com.hunnit_beasts.doro.bff.store;

import com.hunnit_beasts.doro.bff.core.BffLoginAttempt;
import com.hunnit_beasts.doro.bff.core.BffLoginAttemptStore;
import com.hunnit_beasts.doro.bff.core.BffSession;
import com.hunnit_beasts.doro.bff.core.BffSessionStore;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 한 서비스 몫의 SDK 저장소 계약({@link BffSessionStore}, {@link BffLoginAttemptStore}). 모든 작업에 서비스 이름을 붙여 {@link BffStorage} 로 넘긴다. */
public class ServiceStores implements BffSessionStore, BffLoginAttemptStore {

    private final String service;
    private final BffStorage storage;

    public ServiceStores(String service, BffStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @Override public Optional<BffSession> find(String sessionHash) { return storage.findSession(service, sessionHash); }
    @Override public void create(BffSession session, String previousSessionHash) { storage.createSession(service, session, previousSessionHash); }
    @Override public void updateTokens(String hash, String access, Instant accessExpiresAt, String refresh) {
        storage.updateTokens(service, hash, access, accessExpiresAt, refresh);
    }
    @Override public void touchLastUsed(String hash, Instant now) { storage.touchLastUsed(service, hash, now); }
    @Override public void delete(String hash) { storage.deleteSession(service, hash); }
    @Override public int deleteAllByUserId(UUID userId) { return storage.deleteAllSessionsByUserId(userId); }
    @Override public int deleteExpired(Instant now) { return storage.deleteExpired(now); }
    @Override public void save(BffLoginAttempt attempt) { storage.saveAttempt(service, attempt); }
    @Override public Optional<BffLoginAttempt> consume(String stateHash) { return storage.consumeAttempt(service, stateHash); }
}
