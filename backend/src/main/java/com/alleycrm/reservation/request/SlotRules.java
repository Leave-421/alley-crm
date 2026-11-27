package com.alleycrm.reservation.request;

import com.alleycrm.common.time.ServiceTime;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 슬롯 계산(D5, FR-RSV-05 · FR-STO-02). 예약 신청 검사(단계 30)와 예약 가능 시간 조회가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>슬롯은 영업 시작 시각부터 슬롯 단위로 나눈다. 마지막 슬롯은 영업 종료 시각까지 끝나야 한다.</li>
 *   <li>종료 시각이 시작 시각보다 이르거나 같으면 다음 날 새벽까지 영업한다(예: 18:00 ~ 02:00).
 *       이때 자정 뒤 슬롯도 시작한 날(영업일)의 것이다.</li>
 *   <li>휴무일은 영업일의 요일로 판단한다.</li>
 * </ul>
 */
public final class SlotRules {

    /** 서비스 운영 시간대(05-policy) */
    public static final ZoneId SERVICE_ZONE = ServiceTime.ZONE;

    private SlotRules() {
    }

    /** 그 영업일의 슬롯 시작 시각들. 휴무일이거나 영업시간이 비어 있으면 빈 목록 */
    public static List<Instant> slotsOf(StoreSnapshot store, LocalDate businessDay) {
        if (store.openTime() == null || store.closeTime() == null || isClosedDay(store, businessDay)) {
            return List.of();
        }
        LocalDateTime open = businessDay.atTime(store.openTime());
        LocalDateTime close = closeOf(store, businessDay);
        List<Instant> slots = new ArrayList<>();
        for (LocalDateTime t = open; !t.plusMinutes(store.slotMinutes()).isAfter(close); t = t.plusMinutes(store.slotMinutes())) {
            slots.add(t.atZone(SERVICE_ZONE).toInstant());
        }
        return slots;
    }

    /** 이 시각이 가게의 슬롯 시작 시각인가(영업시간 안 · 휴무일 아님 · 슬롯 단위에 맞음) */
    public static boolean isSlotStart(StoreSnapshot store, Instant startAt) {
        LocalDateTime local = LocalDateTime.ofInstant(startAt, SERVICE_ZONE);
        // 자정 뒤 새벽 영업이면 전날 영업일의 슬롯일 수 있다
        for (LocalDate day : List.of(local.toLocalDate(), local.toLocalDate().minusDays(1))) {
            if (slotsOf(store, day).contains(startAt)) {
                return true;
            }
        }
        return false;
    }

    static LocalDateTime closeOf(StoreSnapshot store, LocalDate businessDay) {
        LocalTime open = store.openTime();
        LocalTime close = store.closeTime();
        return close.isAfter(open) ? businessDay.atTime(close) : businessDay.plusDays(1).atTime(close);
    }

    static boolean isClosedDay(StoreSnapshot store, LocalDate day) {
        return closedDays(store.closedDays()).contains(day.getDayOfWeek());
    }

    /** "mon,tue" → 요일 집합. 빈 값이면 연중무휴 */
    static Set<DayOfWeek> closedDays(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(SlotRules::dayOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static DayOfWeek dayOf(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "mon" -> DayOfWeek.MONDAY;
            case "tue" -> DayOfWeek.TUESDAY;
            case "wed" -> DayOfWeek.WEDNESDAY;
            case "thu" -> DayOfWeek.THURSDAY;
            case "fri" -> DayOfWeek.FRIDAY;
            case "sat" -> DayOfWeek.SATURDAY;
            case "sun" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalStateException("알 수 없는 휴무 요일: " + code);
        };
    }
}
