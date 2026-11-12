package com.hunnit_beasts.doro.bff.sync;

import com.hunnit_beasts.doro.bff.store.BffStorage;
import com.hunnit_beasts.doro.bff.store.SyncStateEntity;
import com.hunnit_beasts.doro.bff.store.SyncStateRepository;
import com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersProperties;
import com.hunnit_beasts.doro.sdk.sync.SyncCursorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 탈퇴 동기화(SDK): Doro 에서 영구 탈퇴한 사용자의 BFF 세션(암호화된 토큰)을 모든 서비스에서 지운다.
 * 각 서비스의 데이터 정리는 서비스가 같은 SDK 모듈로 따로 한다.
 */
@Configuration
public class BffSyncConfig {

    @Bean
    @ConfigurationProperties(prefix = "doro.bff.deleted-sync")
    public DeletedUsersProperties deletedUsersProperties() {
        return new DeletedUsersProperties();
    }

    @Bean
    public DeletedUserHandler deletedUserHandler(BffStorage storage) {
        // 세션이 하나라도 지워졌으면 true. 멱등하다.
        return (userId, deletedAt) -> storage.deleteAllSessionsByUserId(userId) > 0;
    }

    @Component
    static class DbSyncCursorStore implements SyncCursorStore {

        private final SyncStateRepository states;

        DbSyncCursorStore(SyncStateRepository states) {
            this.states = states;
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<Instant> get(String name) {
            return states.findById(name).map(SyncStateEntity::getCursorAt);
        }

        @Override
        @Transactional
        public void advance(String name, Instant cursor) {
            SyncStateEntity state = states.findById(name).orElseGet(() -> new SyncStateEntity(name, cursor));
            if (cursor.isAfter(state.getCursorAt())) {
                state.advanceTo(cursor);
            }
            states.save(state);
        }
    }
}
