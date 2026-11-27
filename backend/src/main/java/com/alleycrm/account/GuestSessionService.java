package com.alleycrm.account;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.customer.CustomerService;
import com.alleycrm.reservation.GuestReservationItem;
import com.alleycrm.reservation.GuestReservationReader;
import com.alleycrm.reservation.ReservationResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비회원 예약 조회(C13, FR-AUTH-03). 예약번호 + 전화번호가 맞으면 그 전화번호의 비회원 예약만 다루는 제한 세션을 준다.
 * 다른 기능(D의 손님 예약 변경 · 취소 등)은 {@link #reservationIds}로 요청자의 예약인지 확인한다.
 */
@Service
public class GuestSessionService {

    private final CustomerService customers;
    private final GuestReservationReader reservations;

    GuestSessionService(CustomerService customers, GuestReservationReader reservations) {
        this.customers = customers;
        this.reservations = reservations;
    }

    /** 맞지 않으면 예약번호가 없는지 전화번호가 틀렸는지 구분하지 않고 401 unauthorized(FR-AUTH-03 #3). */
    @Transactional(readOnly = true)
    public ReservationResponse lookup(String reservationNo, String phone) {
        return reservations.findByNoForCustomers(reservationNo, customers.guestIdsByPhone(phone))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "예약번호 또는 전화번호가 맞지 않습니다."));
    }

    /** 제한 세션의 예약, 방문 일시가 늦은 것부터. */
    @Transactional(readOnly = true)
    public List<GuestReservationItem> reservations(GuestPrincipal guest) {
        return reservations.listForCustomers(customers.guestIdsByPhone(guest.phone()));
    }

    /** 제한 세션이 다룰 수 있는 예약 ID(그 전화번호의 비회원 예약). */
    @Transactional(readOnly = true)
    public List<Long> reservationIds(GuestPrincipal guest) {
        return reservations.idsForCustomers(customers.guestIdsByPhone(guest.phone()));
    }
}
