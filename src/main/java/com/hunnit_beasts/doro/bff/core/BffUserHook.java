package com.hunnit_beasts.doro.bff.core;

import com.hunnit_beasts.doro.sdk.domain.DoroUser;

/**
 * 로그인에 성공한 직후 호출된다. 사용자 행이 필요한 쪽이 만들거나 갱신한다(JIT 생성).
 * 예외가 나면 로그인은 실패로 처리된다.
 */
@FunctionalInterface
public interface BffUserHook {

    void onLogin(DoroUser user);

    /** 아무 것도 하지 않는다(서비스가 사용자 행을 따로 두지 않는 경우). */
    static BffUserHook none() {
        return user -> { };
    }
}
