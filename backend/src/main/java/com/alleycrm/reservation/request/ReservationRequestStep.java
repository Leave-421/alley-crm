package com.alleycrm.reservation.request;

/**
 * 예약 신청 처리 단계(CONTRACTS_API 「예약 신청 처리 단계」).
 * 각 담당은 자기 단계 클래스만 스프링 빈으로 만든다. {@link ReservationRequestService}가 order 순서대로,
 * 한 트랜잭션 안에서 부른다. 실패는 {@link com.alleycrm.common.error.ApiException}으로 알린다.
 */
public interface ReservationRequestStep {

    /** 입력 검증 (D2) */
    int VALIDATE = 10;
    /** 손님 식별 · 역할 확인 (C4) */
    int IDENTIFY_CUSTOMER = 20;
    /** 예약 가능 확인 (D2 · D5) */
    int AVAILABILITY = 30;
    /** 코스 연결 (D12) */
    int COURSE = 40;
    /** 공유 동의 기록 (C8) */
    int CONSENT = 50;
    /** 쿠폰 적용 (E13) */
    int COUPON = 60;
    /** 예약금 (D10) */
    int DEPOSIT = 70;
    /** 저장 (D2) */
    int SAVE = 80;

    int order();

    void apply(ReservationRequestContext ctx);
}
