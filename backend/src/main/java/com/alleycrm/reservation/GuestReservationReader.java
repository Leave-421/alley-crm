package com.alleycrm.reservation;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비회원 예약 조회(C13)가 쓰는 읽기 전용 조회. 손님 ID는 부르는 쪽(제한 세션의 전화번호)이 정한다.
 * 비회원은 쿠폰을 쓰지 않아(FR-CPN-06 #3) 응답의 {@code coupon}은 항상 null이다.
 */
@Service
public class GuestReservationReader {

    private final EntityManager em;

    GuestReservationReader(EntityManager em) {
        this.em = em;
    }

    /** 그 손님들의 예약, 방문 일시가 늦은 것부터. */
    @Transactional(readOnly = true)
    public List<ReservationResponse> listForCustomers(Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        SELECT r FROM Reservation r WHERE r.customerId IN :ids ORDER BY r.startAt DESC, r.id DESC""",
                        Reservation.class)
                .setParameter("ids", customerIds)
                .getResultStream().map(r -> ReservationResponse.of(r, null)).toList();
    }

    /** 예약번호가 그 손님들의 예약이면 그 예약. 아니면 비어 있다(남의 예약인지 구분하지 않음). */
    @Transactional(readOnly = true)
    public Optional<ReservationResponse> findByNoForCustomers(String reservationNo, Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Optional.empty();
        }
        return em.createQuery("""
                        SELECT r FROM Reservation r WHERE r.reservationNo = :no AND r.customerId IN :ids""",
                        Reservation.class)
                .setParameter("no", reservationNo)
                .setParameter("ids", customerIds)
                .getResultStream().findFirst().map(r -> ReservationResponse.of(r, null));
    }

    /** 그 손님들의 예약 ID. 비회원 알림(받는 쪽 = 예약)을 고를 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Long> idsForCustomers(Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT r.id FROM Reservation r WHERE r.customerId IN :ids", Long.class)
                .setParameter("ids", customerIds)
                .getResultList();
    }
}
