package com.alleycrm.store;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalTime;
import java.util.List;

/** PUT /api/v1/owner/store/hours 요청. 규칙은 가입 신청의 영업시간 · 휴무일과 같다. */
record OwnerStoreHoursRequest(
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        List<@NotNull @Pattern(regexp = "mon|tue|wed|thu|fri|sat|sun") String> closedDays) {
}
