package com.alleycrm.recommend.record;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.domain.DayType;
import com.alleycrm.recommend.domain.TimeBand;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/course-previews} — 오늘의 코스 노출 · 클릭 기록(CONTRACTS_API 3.3). 로그인 없이 부르는 경로이고,
 * 보안 설정에 여는 것은 C(#24)가 한다(3.3 V-2). 응답에 방문자 쿠키가 실릴 수 있다.
 */
@RestController
class CoursePreviewController {

    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";
    private static final int COURSE_SIZE = 3;

    record CoursePreviewRequest(String type, List<String> storeIds, String dayType, String timeBand) {
    }

    private final ExplorationRecordService service;

    CoursePreviewController(ExplorationRecordService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/course-previews")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@RequestBody(required = false) CoursePreviewRequest body) {
        if (body == null) {
            throw invalid();
        }
        CoursePreviewType type = CoursePreviewType.fromCode(body.type()).orElseThrow(CoursePreviewController::invalid);
        DayType dayType = DayType.fromCode(body.dayType()).orElseThrow(CoursePreviewController::invalid);
        TimeBand timeBand = TimeBand.fromCode(body.timeBand()).orElseThrow(CoursePreviewController::invalid);
        List<String> raw = body.storeIds();
        if (raw == null || raw.size() != COURSE_SIZE || new HashSet<>(raw).size() != COURSE_SIZE
                || !raw.stream().allMatch(id -> id != null && id.matches(ID_PATTERN))) {
            throw invalid();
        }
        service.recordCoursePreview(type, raw.stream().map(Long::parseLong).toList(), dayType, timeBand);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID);
    }
}
