package com.alleycrm.reservation.request;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.store.StoreJoinStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 30 예약 가능 확인 (D2 · D5).
 * <ul>
 *   <li>D2: 가게 존재 · 상태, 지난 시각, 예약 가능 기간, 슬롯 자리(동시에 받는 예약 수)</li>
 *   <li>D5: 한 팀 최대 인원, 영업시간 · 휴무일 · 슬롯 단위 맞춤({@link SlotRules})</li>
 * </ul>
 * 같은 가게의 신청은 가게 행을 잠가(FOR UPDATE) 한 줄로 세운다. 동시에 들어와도 자리를 넘겨 받지 않는다.
 */
@Component
class AvailabilityStep implements ReservationRequestStep {

    private final StoreSlotReader slots;

    AvailabilityStep(StoreSlotReader slots) {
        this.slots = slots;
    }

    @Override
    public int order() {
        return AVAILABILITY;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        StoreSnapshot store = slots.lock(ctx.getStoreId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        // 참여 가게(승인 · 탈퇴 신청 중)가 아니면 손님에게 없는 가게다
        if (!StoreJoinStatus.isParticipating(store.joinStatus())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        ctx.setStore(store);

        // 한 팀 최대 인원(FR-RSV-05 #3, 11-24 (5)) — 입력 문제라 invalid
        if (store.maxPartySize() != null && ctx.getPartySize() > store.maxPartySize()) {
            throw new ApiException(ErrorCode.INVALID);
        }
        if (!"open".equals(store.businessStatus()) || !store.reservationEnabled()) {
            throw slotUnavailable();
        }
        if (!ctx.getStartAt().isAfter(ctx.getRequestedAt())) {
            throw slotUnavailable();
        }
        // 예약 가능 기간: 운영 시간대 기준 오늘부터 booking_window_days일 뒤 날짜까지(FR-RSV-01 #4, FR-RSV-05 #4 · #5)
        LocalDate today = LocalDate.ofInstant(ctx.getRequestedAt(), SlotRules.SERVICE_ZONE);
        LocalDate day = LocalDate.ofInstant(ctx.getStartAt(), SlotRules.SERVICE_ZONE);
        if (day.isAfter(today.plusDays(store.bookingWindowDays()))) {
            throw slotUnavailable();
        }
        // 영업시간 · 휴무일 · 슬롯 단위(D5)
        if (!SlotRules.isSlotStart(store, ctx.getStartAt())) {
            throw slotUnavailable();
        }
        // 슬롯 자리: 같은 시작 시각의 신청 · 확정 예약 수(FR-RSV-01 #3, FR-RSV-05 #2)
        if (slots.taken(store.id(), ctx.getStartAt()) >= store.capacityPerSlot()) {
            throw slotUnavailable();
        }
    }

    private static ApiException slotUnavailable() {
        return new ApiException(ErrorCode.SLOT_UNAVAILABLE);
    }
}
