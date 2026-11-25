package com.alleycrm.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/auth/signup 요청. 값 목록은 CONTRACTS.md 「3」. */
record SignupRequest(
        @NotNull @Pattern(regexp = "[a-z0-9]{4,30}") String loginId,
        @NotNull @Size(min = 8, max = 64) String password,
        @NotBlank @Size(max = 20) String name,
        @Pattern(regexp = "female|male") String gender,
        @Pattern(regexp = "age_20_or_under|age_30s|age_40s|age_50_or_over") String ageGroup) {
}
