package com.alleycrm.reservation.request;

import com.alleycrm.reservation.Reservation;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 신청 단계들이 같이 쓰는 값. 단계는 자기 담당 칸만 채우고, 앞 단계가 채운 칸은 읽기만 한다.
 */
@Getter
@Setter
public class ReservationRequestContext {

    /** 원래 요청 본문 */
    private final ReservationRequestBody body;
    /** 신청 시각(서버 시계). 소개 마감 · 쿠폰 기한 판단 기준 */
    private final Instant requestedAt;
    /** 로그인한 계정의 이름(로그인 안 했으면 null). 회원 · 역할 판단은 손님 식별 단계(20, C4)가 한다 */
    private final String loginName;

    // 10 입력 검증 (D2): 요청 값을 확인해 숫자 ID로 바꿔 둔다
    private Long storeId;
    private Instant startAt;
    private int partySize;
    private Long couponId;
    private Long courseRootId;
    private Long courseProposalId;

    // 20 손님 식별 (C4)
    private Long customerId;

    // 30 예약 가능 확인 (D2 · D5)
    private StoreSnapshot store;

    // 40 코스 연결 (D12)
    private Long senderStoreId;

    // 60 쿠폰 적용 (E13)
    private AppliedCoupon appliedCoupon;

    // 70 예약금 (D10)
    private int depositAmount;

    // 80 저장 (D2)
    private Reservation reservation;

    public ReservationRequestContext(ReservationRequestBody body, Instant requestedAt, String loginName) {
        this.body = body;
        this.requestedAt = requestedAt;
        this.loginName = loginName;
    }

    public boolean loggedIn() {
        return loginName != null;
    }
}
