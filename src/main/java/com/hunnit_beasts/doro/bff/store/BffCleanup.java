package com.hunnit_beasts.doro.bff.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 만료된 세션과 끝나지 않은 로그인 시도를 주기적으로 지운다. */
@Component
public class BffCleanup {

    private static final Logger log = LoggerFactory.getLogger(BffCleanup.class);

    private final BffStorage storage;

    public BffCleanup(BffStorage storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        int count = storage.deleteExpired(Instant.now());
        if (count > 0) {
            log.info("Purged expired BFF rows: {}", count);
        }
    }
}
