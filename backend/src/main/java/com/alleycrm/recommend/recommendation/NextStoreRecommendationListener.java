package com.alleycrm.recommend.recommendation;

import com.alleycrm.reservation.request.ReservationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 신청이 커밋된 뒤 다음 가게 추천 알림을 계산한다(E8, CONTRACTS_API 3.11). 예약은 이미 저장됐으므로 계산이 실패해도
 * 예약 요청에 영향을 주지 않게 로그만 남긴다(알림만 생기지 않는다).
 */
@Component
class NextStoreRecommendationListener {

    private static final Logger log = LoggerFactory.getLogger(NextStoreRecommendationListener.class);

    private final RecommendationService recommendations;

    NextStoreRecommendationListener(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @TransactionalEventListener
    void onRequested(ReservationRequestedEvent event) {
        try {
            recommendations.announceNextStores(event.reservationId());
        } catch (RuntimeException e) {
            log.warn("다음 가게 추천 알림을 계산하지 못했습니다: reservation {}", event.reservationId(), e);
        }
    }
}
