package com.hunnit_beasts.doro.bff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway 마이그레이션이 만든 스키마를 JPA 엔티티가 그대로 검증(ddl-auto=validate)하는지 본다. 운영과 같은 경로다. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:doro_bff_migration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class BffMigrationSchemaValidationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("마이그레이션이 만든 테이블을 엔티티가 검증하고, 서비스 컬럼을 포함한다")
    void migrationMatchesEntities() {
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE service IS NULL", Integer.class);
        Integer attempts = jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE service IS NULL", Integer.class);
        assertThat(sessions).isZero();
        assertThat(attempts).isZero();
    }
}
