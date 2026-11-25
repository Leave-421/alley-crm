package com.alleycrm.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** POST /api/v1/store-joins 요청: 사장님 계정(회원가입과 같은 규칙) + 권역 + 가게 정보. */
record StoreJoinRequest(
        @NotNull @Pattern(regexp = "[a-z0-9]{4,30}") String loginId,
        @NotNull @Size(min = 8, max = 64) String password,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String regionId,
        String name,
        String category,
        String alley,
        LocalTime openTime,
        LocalTime closeTime,
        List<String> closedDays) {

    StoreJoinInfo info() {
        return new StoreJoinInfo(name, category, alley, openTime, closeTime, closedDays);
    }
}
