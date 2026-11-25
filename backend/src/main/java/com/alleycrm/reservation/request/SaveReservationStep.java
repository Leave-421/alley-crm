package com.alleycrm.reservation.request;

import com.alleycrm.reservation.Reservation;
import com.alleycrm.reservation.ReservationRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 80 저장 (D2): 예약번호를 만들고 신청 상태로 저장한다. */
@Component
class SaveReservationStep implements ReservationRequestStep {

    /** 영문 대문자 · 숫자에서 헷갈리는 0 O 1 I를 뺀 32자(계약 「예약 응답」 reservationNo) */
    static final String NO_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int NO_LENGTH = 8;
    private static final int MAX_TRIES = 5;

    private final ReservationRepository reservations;
    private final SecureRandom random = new SecureRandom();

    SaveReservationStep(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public int order() {
        return SAVE;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        if (ctx.getCustomerId() == null) {
            throw new IllegalStateException("손님 식별 단계(20, C4)가 손님을 정하지 않았다");
        }
        Reservation r = Reservation.requested(newReservationNo(), ctx.getStoreId(), ctx.getCustomerId(),
                ctx.getStartAt(), ctx.getPartySize(), ctx.getRequestedAt(), ctx.getDepositAmount(),
                ctx.getBody().requestNote(), ctx.getCourseRootId(), ctx.getCourseProposalId(),
                ctx.getSenderStoreId());
        ctx.setReservation(reservations.saveAndFlush(r));
    }

    private String newReservationNo() {
        for (int i = 0; i < MAX_TRIES; i++) {
            StringBuilder sb = new StringBuilder(NO_LENGTH);
            for (int j = 0; j < NO_LENGTH; j++) {
                sb.append(NO_ALPHABET.charAt(random.nextInt(NO_ALPHABET.length())));
            }
            String no = sb.toString();
            if (!reservations.existsByReservationNo(no)) {
                return no;
            }
        }
        throw new IllegalStateException("예약번호를 만들지 못했다");
    }
}
