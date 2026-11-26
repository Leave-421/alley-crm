package com.alleycrm.coupon.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 쿠폰 사용 확인에 필요한 예약 상태 읽기. 예약 표(D 영역)는 읽기만 하고, 가게 조건을 함께 건다(IDOR). */
@Repository
public class ReservationStatusReader {

    private final JdbcTemplate jdbc;

    ReservationStatusReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 가게의 예약이면 상태(`reservation.status`), 없거나 다른 가게 예약이면 빈 값. */
    public Optional<String> findStatusOfStoreReservation(long reservationId, long storeId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM reservation WHERE id = ? AND store_id = ?", String.class, reservationId, storeId);
        return rows.stream().findFirst();
    }
}
