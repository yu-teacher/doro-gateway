package com.hunnit_beasts.doro.bff.config;

import com.hunnit_beasts.doro.bff.core.BffAuthService;
import com.hunnit_beasts.doro.bff.core.BffController;
import com.hunnit_beasts.doro.bff.core.BffCookies;
import com.hunnit_beasts.doro.bff.core.BffProperties;

/** 한 서비스의 BFF 구성 요소 묶음. */
public record ServiceBff(String name, BffProperties props, BffCookies cookies, BffAuthService auth, BffController controller) {}
