package com.alleycrm.recommend.todaycourse;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.todaycourse.TodayCourseService.TodayCoursesResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 오늘의 코스(E10, CONTRACTS_API 3.7). 누구나 쓴다 — 로그인 없이 여는 보안 설정은 C(#24, 3.7 T-1)가 더한다. */
@RestController
class TodayCourseController {

    private static final String ID_PATTERN = "[1-9][0-9]{0,17}";

    private final TodayCourseService service;

    TodayCourseController(TodayCourseService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/today-courses")
    ResponseEntity<TodayCoursesResponse> todayCourses(@RequestParam(required = false) String regionId) {
        if (regionId == null || !regionId.matches(ID_PATTERN)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.todayCourses(Long.parseLong(regionId)));
    }
}
