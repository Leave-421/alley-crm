package com.alleycrm.store;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** PUT /api/v1/owner/store/status 요청. 값 목록은 CONTRACTS.md 「3」 `store.businessStatus`. */
record OwnerStoreStatusRequest(
        @NotNull @Pattern(regexp = "open|paused|closed") String businessStatus,
        @NotNull Boolean reservationEnabled) {
}
