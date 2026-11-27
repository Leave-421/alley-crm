package com.alleycrm.common.scheduler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 등록된 자동 작업을 이름 순으로 모두 돈다. 한 작업이 실패해도 나머지는 돈다(실패는 로그와 -1).
 * 주기 실행은 {@link SchedulingConfig}, 시연 시계를 바꿀 때는 DemoClockController가 바로 부른다.
 */
@Component
public class ScheduledJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

    private final List<ScheduledJob> jobs;

    public ScheduledJobRunner(List<ScheduledJob> jobs) {
        this.jobs = jobs.stream().sorted(Comparator.comparing(ScheduledJob::name)).toList();
    }

    /** @return 작업 이름 → 처리 건수(실패면 -1) */
    public synchronized Map<String, Integer> runAll() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ScheduledJob job : jobs) {
            try {
                result.put(job.name(), job.run());
            } catch (RuntimeException e) {
                log.error("자동 작업 실패: {}", job.name(), e);
                result.put(job.name(), -1);
            }
        }
        return result;
    }

    public List<String> jobNames() {
        return jobs.stream().map(ScheduledJob::name).toList();
    }
}
