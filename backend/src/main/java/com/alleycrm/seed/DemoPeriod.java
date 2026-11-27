package com.alleycrm.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 가상 데이터의 기간(#315). 시드를 돌린 날(운영 시간대)을 '오늘'로 두고, 지난 기록은 어제까지 91일, 다가올 예약은 오늘부터 만든다.
 * 고정 날짜를 쓰지 않으므로 언제 시드를 돌려도 지난 기록이 미래에 있거나, 다가올 예약이 없는 일이 생기지 않는다.
 *
 * @param today 시드를 돌린 날(운영 시간대)
 * @param start 지난 기록의 첫날
 * @param end 지난 기록의 마지막 날(어제)
 */
public record DemoPeriod(LocalDate today, LocalDate start, LocalDate end) {

    /** 지난 기록 일수(학기 한 철, NFR-07). */
    static final int HISTORY_DAYS = 91;

    public DemoPeriod {
        if (start.isAfter(end) || !end.isBefore(today)) {
            throw new IllegalArgumentException("가상 데이터 기간이 맞지 않습니다: " + start + " ~ " + end + ", 오늘 " + today);
        }
    }

    /** 오늘 기준 기간: 어제까지 {@link #HISTORY_DAYS}일. */
    public static DemoPeriod endingBefore(LocalDate today) {
        LocalDate end = today.minusDays(1);
        return new DemoPeriod(today, end.minusDays(HISTORY_DAYS - 1), end);
    }

    /** 서버 시각 기준 기간(오늘 = 그 시각의 운영 시간대 날짜). */
    public static DemoPeriod at(Instant now) {
        return endingBefore(LocalDate.ofInstant(now, DemoReservationPlanner.SERVICE_ZONE));
    }

    /** 지난 기록의 일수. */
    int days() {
        return (int) (end.toEpochDay() - start.toEpochDay()) + 1;
    }

    /** 지난 기록의 끝: 오늘 00:00(운영 시간대). */
    Instant historyEnd() {
        return today.atStartOfDay(DemoReservationPlanner.SERVICE_ZONE).toInstant();
    }

    /** 정산을 확정해 둘 마지막 달: 어제가 속한 달의 전달(어제가 속한 달은 집계 중으로 둔다). */
    YearMonth lastConfirmedMonth() {
        return YearMonth.from(end).minusMonths(1);
    }
}
