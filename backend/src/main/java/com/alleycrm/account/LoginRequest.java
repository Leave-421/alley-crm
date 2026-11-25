package com.alleycrm.account;

import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/auth/login 요청. */
record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
}
