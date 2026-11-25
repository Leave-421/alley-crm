package com.alleycrm.common.time;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연용 시각 조작(D18). {@code app.demo-tools.enabled=true}일 때만 생긴다(기본 꺼짐).
 * 실제 운영에서는 켜지 않는다. 시연 도구 화면(B20)이 부른다.
 */
@RestController
@RequestMapping("/api/v1/demo/clock")
@ConditionalOnProperty(name = "app.demo-tools.enabled", havingValue = "true")
public class DemoClockController {

    private final AppClock clock;

    public DemoClockController(AppClock clock) {
        this.clock = clock;
    }

    public record ClockResponse(Instant now, long offsetSeconds) {
    }

    /** advanceMinutes(앞당길 분, 음수 가능)와 at(맞출 시각) 중 하나만 보낸다. */
    public record ClockRequest(Long advanceMinutes, Instant at) {
    }

    @GetMapping
    ClockResponse get() {
        return response();
    }

    @PostMapping
    ClockResponse change(@RequestBody ClockRequest request) {
        boolean hasAdvance = request.advanceMinutes() != null;
        boolean hasAt = request.at() != null;
        if (hasAdvance == hasAt) {
            throw new ApiException(ErrorCode.INVALID, "advanceMinutes와 at 중 하나만 보내 주세요.");
        }
        if (hasAdvance) {
            clock.advance(Duration.ofMinutes(request.advanceMinutes()));
        } else {
            clock.setTo(request.at());
        }
        return response();
    }

    @DeleteMapping
    ResponseEntity<ClockResponse> reset() {
        clock.reset();
        return ResponseEntity.ok(response());
    }

    private ClockResponse response() {
        return new ClockResponse(clock.instant(), clock.offset().toSeconds());
    }
}
