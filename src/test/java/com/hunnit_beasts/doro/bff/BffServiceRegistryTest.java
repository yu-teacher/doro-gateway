package com.hunnit_beasts.doro.bff;

import com.hunnit_beasts.doro.bff.config.BffServiceRegistry;
import com.hunnit_beasts.doro.bff.config.DoroBffProperties;
import com.hunnit_beasts.doro.bff.store.BffStorage;
import com.hunnit_beasts.doro.sdk.security.DoroTokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 서비스 설정 검증: 서비스끼리 OAuth 클라이언트나 쿠키 이름이 겹치면 기동하지 않는다. */
class BffServiceRegistryTest {

    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    private static DoroBffProperties.Service service(String client, String cookie, String loginCookie) {
        DoroBffProperties.Service s = new DoroBffProperties.Service();
        s.setClientId(client);
        s.setCookieName(cookie);
        s.setLoginCookieName(loginCookie);
        s.setCsrfHeader("X-Csrf");
        s.setRedirectUri("https://doro.example/x/api/v1/bff/callback");
        return s;
    }

    private static BffServiceRegistry registry(Consumer<DoroBffProperties> customize) {
        DoroBffProperties props = new DoroBffProperties();
        props.setSessionEncryptionKey(KEY);
        customize.accept(props);
        return new BffServiceRegistry(props, mock(BffStorage.class), mock(DoroTokenVerifier.class));
    }

    @Test
    @DisplayName("정상 설정은 서비스마다 구성이 만들어진다")
    void buildsEachService() {
        BffServiceRegistry r = registry(p -> {
            p.getServices().put("a", service("c-a", "a_s", "a_l"));
            p.getServices().put("b", service("c-b", "b_s", "b_l"));
        });
        assertThat(r.find("a")).isPresent();
        assertThat(r.find("b")).isPresent();
        assertThat(r.find("c")).isEmpty();
        assertThat(r.find(null)).isEmpty();
    }

    @Test
    @DisplayName("서비스가 하나도 없으면 기동하지 않는다")
    void requiresServices() {
        assertThatThrownBy(() -> registry(p -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("OAuth 클라이언트 ID 가 겹치면 기동하지 않는다")
    void duplicateClientId() {
        assertThatThrownBy(() -> registry(p -> {
            p.getServices().put("a", service("same", "a_s", "a_l"));
            p.getServices().put("b", service("same", "b_s", "b_l"));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("client-id");
    }

    @Test
    @DisplayName("세션/로그인 쿠키 이름이 겹치면 기동하지 않는다")
    void duplicateCookieName() {
        assertThatThrownBy(() -> registry(p -> {
            p.getServices().put("a", service("c-a", "dup", "a_l"));
            p.getServices().put("b", service("c-b", "dup", "b_l"));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("cookie-name");
        assertThatThrownBy(() -> registry(p -> {
            p.getServices().put("a", service("c-a", "a_s", "x"));
            p.getServices().put("b", service("c-b", "x", "b_l"));
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("필수 설정이 비면 기동하지 않고, 서비스 이름 형식도 검사한다")
    void validatesRequiredAndNames() {
        DoroBffProperties.Service noClient = service("", "a_s", "a_l");
        assertThatThrownBy(() -> registry(p -> p.getServices().put("a", noClient)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("client-id");
        assertThatThrownBy(() -> registry(p -> p.getServices().put("Bad Name", service("c", "s", "l"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry(p -> {
            p.setSessionEncryptionKey(null);
            p.getServices().put("a", service("c", "s", "l"));
        })).isInstanceOf(IllegalStateException.class);
    }
}
