package com.alleycrm.common.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 자동 작업 주기 실행(D7). `app.scheduler.enabled=true`일 때만 켠다(기본 켜짐, 테스트는 끔).
 * 주기는 `app.scheduler.interval`(기본 1분).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true")
class SchedulingConfig {

    private final ScheduledJobRunner runner;

    SchedulingConfig(ScheduledJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.interval:PT1M}", initialDelayString = "${app.scheduler.interval:PT1M}")
    void tick() {
        runner.runAll();
    }
}
