package com.alleycrm.reservation.request;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 손님 예약 화면(G-02)의 날짜별 예약 가능 시간(D5). 로그인 불필요.
 * 예약 신청 검사(단계 30)와 같은 규칙({@link SlotRules})으로 계산한다. 화면에 '가능'으로 보인 시각도
 * 신청 순간에 자리가 찼으면 신청은 slot_unavailable이 될 수 있다.
 */
@RestController
class AvailableSlotsController {

    private final StoreSlotReader slots;
    private final Clock clock;

    AvailableSlotsController(StoreSlotReader slots, Clock clock) {
        this.slots = slots;
        this.clock = clock;
    }

    @GetMapping("/api/v1/stores/{storeId}/available-slots")
    Map<String, Object> availableSlots(@PathVariable long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StoreSnapshot store = slots.read(storeId)
                .filter(s -> "approved".equals(s.joinStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, SlotRules.SERVICE_ZONE);
        boolean accepting = "open".equals(store.businessStatus()) && store.reservationEnabled();
        boolean inWindow = !date.isBefore(today) && !date.isAfter(today.plusDays(store.bookingWindowDays()));

        List<Instant> starts = SlotRules.slotsOf(store, date);
        Map<Instant, Integer> taken = starts.isEmpty() ? Map.of()
                : slots.takenBetween(storeId, starts.get(0), starts.get(starts.size() - 1).plusSeconds(1));
        List<Map<String, Object>> items = starts.stream()
                .map(t -> Map.<String, Object>of(
                        "startAt", t,
                        "available", accepting && inWindow && t.isAfter(now)
                                && taken.getOrDefault(t, 0) < store.capacityPerSlot()))
                .toList();

        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("date", date.toString());
        body.put("acceptingReservations", accepting);
        body.put("maxPartySize", store.maxPartySize());
        body.put("depositPerPerson", store.depositPerPerson());
        body.put("slots", items);
        return body;
    }
}
