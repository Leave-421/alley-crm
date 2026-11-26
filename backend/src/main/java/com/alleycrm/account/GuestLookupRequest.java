package com.alleycrm.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/guest/lookup 요청. 전화번호 형식은 예약 신청의 비회원 `guest.phone`과 같다. */
record GuestLookupRequest(
        @NotBlank @Size(max = 20) String reservationNo,
        @NotBlank @Pattern(regexp = "[0-9]{10,11}") String phone) {
}
