package com.alleycrm.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 앱 전체가 쓰는 시계. {@link java.time.Clock} 타입으로 주입받아도 같은 인스턴스다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    AppClock appClock() {
        return AppClock.systemUtc();
    }
}
