package com.alleycrm.analytics;

/**
 * '5명 미만' 숨김(FR-ANL-02 · 03 · 05). 묶음의 고유 인원이 5명 미만이면 숫자를 내보내지 않고 {@code belowMinimum}만 알린다.
 * 상인회 화면 · 사장님 손님 흐름이 쓴다. 사장님 세그먼트(FR-ANL-01)에는 쓰지 않는다.
 */
public final class MinimumGroup {

    /** 정해진 값(05-policy 「역할별 열람 범위」 · 추천 실제 기록 최소 고유 손님 수와 같은 기준) */
    public static final int MIN_PEOPLE = 5;

    private MinimumGroup() {
    }

    /** people이 1 ~ 4명이면 count를 숨긴다. 0명(기록 없음)은 아무도 드러내지 않아 0 그대로 준다 */
    public static Masked mask(long count, long people) {
        return people > 0 && people < MIN_PEOPLE ? new Masked(null, true) : new Masked(count, false);
    }

    /** @param count 숨겼으면 null */
    public record Masked(Long count, boolean belowMinimum) {
    }
}
