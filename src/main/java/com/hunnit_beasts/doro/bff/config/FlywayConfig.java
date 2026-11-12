package com.hunnit_beasts.doro.bff.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flyway 마이그레이션. Spring Boot 4 는 Flyway 자동 구성을 별도 모듈로 분리해 이 앱의 의존성에는 없으므로(auth, guard 와 같은 방식으로)
 * 직접 구성하고, JPA 가 스키마를 검증하기 전에 마이그레이션이 끝나도록 순서를 건다.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public static BeanFactoryPostProcessor dependsOnFlywayPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                BeanDefinition bd = beanFactory.getBeanDefinition("entityManagerFactory");
                String[] dependsOn = bd.getDependsOn();
                List<String> list = new ArrayList<>(dependsOn == null ? List.of() : Arrays.asList(dependsOn));
                if (!list.contains("flyway")) {
                    list.add("flyway");
                    bd.setDependsOn(list.toArray(new String[0]));
                }
            }
        };
    }

    @Bean
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
                         @Value("${spring.flyway.table:bff_schema_history}") String table,
                         @Value("${spring.flyway.baseline-on-migrate:true}") boolean baselineOnMigrate) {
        log.info("Initializing BFF Flyway migration: locations={}, table={}", locations, table);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .table(table)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();
        // repair() 는 호출하지 않는다: 체크섬 불일치·실패한 마이그레이션을 조용히 덮지 않고 기동 실패로 드러낸다.
        flyway.migrate();
        return flyway;
    }
}
