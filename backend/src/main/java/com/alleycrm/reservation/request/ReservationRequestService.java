package com.alleycrm.reservation.request;

import com.alleycrm.reservation.Reservation;
import com.alleycrm.reservation.ReservationResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예약 신청: 등록된 단계를 order 순서대로 한 트랜잭션에서 부른다. 아직 없는 단계는 건너뛴다. */
@Service
public class ReservationRequestService {

    private final List<ReservationRequestStep> steps;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ReservationRequestService(List<ReservationRequestStep> steps, Clock clock, ApplicationEventPublisher events) {
        this.steps = steps.stream().sorted(Comparator.comparingInt(ReservationRequestStep::order)).toList();
        this.clock = clock;
        this.events = events;
        long distinct = this.steps.stream().mapToInt(ReservationRequestStep::order).distinct().count();
        if (distinct != this.steps.size()) {
            throw new IllegalStateException("예약 신청 단계의 order가 겹친다: " + this.steps);
        }
    }

    @Transactional
    public ReservationResponse request(ReservationRequestBody body, String loginName) {
        ReservationRequestContext ctx = new ReservationRequestContext(body, Instant.now(clock), loginName);
        for (ReservationRequestStep step : steps) {
            step.apply(ctx);
        }
        Reservation saved = ctx.getReservation();
        if (saved == null) {
            throw new IllegalStateException("저장 단계(80)가 예약을 남기지 않았다");
        }
        events.publishEvent(new ReservationRequestedEvent(saved.getId(), saved.getStoreId(), saved.getCustomerId()));
        return ReservationResponse.of(saved, ctx.getAppliedCoupon());
    }

    /** 등록된 단계 순서(확인용) */
    public List<Integer> stepOrders() {
        return steps.stream().map(ReservationRequestStep::order).toList();
    }
}
