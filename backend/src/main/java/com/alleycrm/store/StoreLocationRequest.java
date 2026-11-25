package com.alleycrm.store;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** PUT /api/v1/stores/{storeId}/location 요청. 좌표 범위는 DB 칸 DECIMAL(7, 4). */
record StoreLocationRequest(
        @Size(max = 200) String address,
        @NotNull @Pattern(regexp = "front_gate|center|back_street") String alley,
        @DecimalMin("-999.9999") @DecimalMax("999.9999") @Digits(integer = 3, fraction = 4) BigDecimal mapX,
        @DecimalMin("-999.9999") @DecimalMax("999.9999") @Digits(integer = 3, fraction = 4) BigDecimal mapY) {
}
