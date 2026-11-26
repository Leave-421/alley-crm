package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 가게 가입 신청 · 상태 · 재신청 · 상인회 승인 · 거절. 계약: CONTRACTS_API 「가게 가입 (C5)」. */
@RestController
class StoreJoinController {

    private final StoreJoinService joins;
    private final Validator validator;

    StoreJoinController(StoreJoinService joins, Validator validator) {
        this.joins = joins;
        this.validator = validator;
    }

    @PostMapping("/api/v1/store-joins")
    @ResponseStatus(HttpStatus.CREATED)
    StoreJoinResponse apply(@Valid @RequestBody StoreJoinRequest body) {
        StoreJoinInfo info = validInfo(body.info());
        return StoreJoinResponse.of(joins.apply(Long.parseLong(body.regionId()), info, body.loginId(), body.password()));
    }

    @GetMapping("/api/v1/store-joins/mine")
    StoreJoinResponse mine(@AuthenticationPrincipal AccountPrincipal me) {
        return StoreJoinResponse.of(joins.mine(me));
    }

    @PostMapping("/api/v1/store-joins/mine/reapply")
    StoreJoinResponse reapply(@AuthenticationPrincipal AccountPrincipal me, @Valid @RequestBody StoreJoinInfo body) {
        return StoreJoinResponse.of(joins.reapply(me, body));
    }

    @GetMapping("/api/v1/store-joins")
    Map<String, Object> list(@AuthenticationPrincipal AccountPrincipal me,
            @RequestParam(defaultValue = StoreJoinStatus.REQUESTED) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || !status.matches(StoreJoinStatus.VALUES)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        Page<Store> result = joins.list(me, status, page, size);
        return Map.of("items", result.map(StoreJoinResponse::of).getContent(), "page", page, "size", size,
                "totalElements", result.getTotalElements(), "totalPages", result.getTotalPages());
    }

    @PostMapping("/api/v1/store-joins/{storeId}/approve")
    StoreJoinResponse approve(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long storeId) {
        return StoreJoinResponse.of(joins.decide(me, storeId, true));
    }

    @PostMapping("/api/v1/store-joins/{storeId}/reject")
    StoreJoinResponse reject(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long storeId) {
        return StoreJoinResponse.of(joins.decide(me, storeId, false));
    }

    /** 신청 본문은 계정 칸과 가게 칸이 한 객체라 가게 칸 검증을 여기서 따로 돌린다 */
    private StoreJoinInfo validInfo(StoreJoinInfo info) {
        if (!validator.validate(info).isEmpty()) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return info;
    }
}
