package com.alleycrm.recommend.record;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/store-clicks} — 가게 클릭 기록(CONTRACTS_API 3.3). 로그인 없이 부르는 경로이고,
 * 보안 설정에 여는 것은 C(#24)가 한다(3.3 V-2). 응답에 방문자 쿠키가 실릴 수 있다.
 */
@RestController
class StoreClickController {

    record StoreClickRequest(String storeId) {
    }

    private final ExplorationRecordService service;

    StoreClickController(ExplorationRecordService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/store-clicks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void click(@RequestBody StoreClickRequest body) {
        service.recordStoreClick(parseId(body == null ? null : body.storeId()));
    }

    /** API의 ID는 문자열이다. 양의 정수가 아니면 400 invalid. */
    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) {
            throw new ApiException(ErrorCode.INVALID, "storeId를 확인해 주세요.");
        }
        return Long.parseLong(value);
    }
}
