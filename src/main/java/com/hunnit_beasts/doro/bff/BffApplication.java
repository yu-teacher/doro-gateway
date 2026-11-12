package com.hunnit_beasts.doro.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.hunnit_beasts.doro.bff.config.DoroBffProperties;

/**
 * Doro BFF. 브라우저의 로그인·세션·토큰 갱신을 서비스(blog, party, games …) 밖에서 한 곳이 맡는다.
 * 서비스는 토큰이나 세션을 알 필요 없이, 게이트웨이가 붙여 주는 {@code Authorization: Bearer} 만 검증한다.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DoroBffProperties.class)
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
