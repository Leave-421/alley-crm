package com.alleycrm.store;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/** 가게 가입 신청 · 재신청의 가게 정보. 값 목록은 CONTRACTS.md 「3」 `store.*`. */
record StoreJoinInfo(
        @NotBlank @Size(max = 50) String name,
        @NotNull @Pattern(regexp = "restaurant|cafe|dessert|pub|workshop|bookstore|gift_shop|stationery") String category,
        @NotNull @Pattern(regexp = "front_gate|center|back_street") String alley,
        @NotNull LocalTime openTime,
        @NotNull LocalTime closeTime,
        List<@NotNull @Pattern(regexp = "mon|tue|wed|thu|fri|sat|sun") String> closedDays) {

    private static final List<String> WEEK = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    /** Bean Validation으로 못 거르는 규칙: 영업 시작 < 끝, 같은 요일 중복 금지 */
    void check() {
        checkHours(openTime, closeTime, closedDays);
    }

    String closedDaysValue() {
        return closedDaysValue(closedDays);
    }

    /** 가입 신청과 사장님 영업시간 변경(C22)이 같이 쓴다. */
    static void checkHours(LocalTime openTime, LocalTime closeTime, List<String> closedDays) {
        if (!openTime.isBefore(closeTime)) {
            throw new ApiException(ErrorCode.INVALID);
        }
        if (closedDays != null && new HashSet<>(closedDays).size() != closedDays.size()) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }

    /** DB 칸 값: 요일 순서대로 쉼표로 잇는다. 없으면 빈 값(연중무휴) */
    static String closedDaysValue(List<String> closedDays) {
        if (closedDays == null) {
            return "";
        }
        return WEEK.stream().filter(closedDays::contains).collect(Collectors.joining(","));
    }
}
