package com.alleycrm.reservation.owner;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사장님 예약 운영 설정(D5, O-03, FR-RSV-05). 영업시간 · 휴무일(O-12)과 영업 상태 · 예약 받기는 C22가 맡는다.
 */
@RestController
class ReservationSettingsController {

    private final OwnerStoreResolver owners;
    private final JdbcTemplate jdbc;
    private final SettingService settings;

    ReservationSettingsController(OwnerStoreResolver owners, JdbcTemplate jdbc, SettingService settings) {
        this.owners = owners;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping("/api/v1/owner/reservation-settings")
    SettingsResponse get(Authentication auth) {
        return load(owners.storeIdOf(auth));
    }

    @PutMapping("/api/v1/owner/reservation-settings")
    @Transactional
    SettingsResponse put(@RequestBody SettingsRequest req, Authentication auth) {
        long storeId = owners.storeIdOf(auth);
        validate(req);
        jdbc.update("""
                UPDATE store SET slot_minutes = ?, capacity_per_slot = ?, max_party_size = ?, booking_window_days = ?,
                                 cancel_deadline_hours = ?, late_cancel_refund_rate = ?, deposit_per_person = ?
                WHERE id = ?
                """, req.slotMinutes(), req.capacityPerSlot(), req.maxPartySize(), req.bookingWindowDays(),
                req.cancelDeadlineHours(), req.lateCancelRefundRate(), req.depositPerPerson(), storeId);
        return load(storeId);
    }

    private SettingsResponse load(long storeId) {
        Map<String, Object> s = jdbc.queryForMap("""
                SELECT slot_minutes, capacity_per_slot, max_party_size, booking_window_days, cancel_deadline_hours,
                       late_cancel_refund_rate, deposit_per_person, region_id
                FROM store WHERE id = ?
                """, storeId);
        long regionId = ((Number) s.get("region_id")).longValue();
        BigDecimal own = (BigDecimal) s.get("late_cancel_refund_rate");
        return new SettingsResponse(
                ((Number) s.get("slot_minutes")).intValue(),
                ((Number) s.get("capacity_per_slot")).intValue(),
                s.get("max_party_size") == null ? null : ((Number) s.get("max_party_size")).intValue(),
                ((Number) s.get("booking_window_days")).intValue(),
                ((Number) s.get("cancel_deadline_hours")).intValue(),
                own,
                own != null ? own : settings.getDecimal(regionId, SettingKey.LATE_CANCEL_REFUND_RATE),
                ((Number) s.get("deposit_per_person")).intValue());
    }

    /** 범위 밖은 400 invalid. 범위의 근거는 계약 「정책에 따라 정한 것 (D5)」 */
    private static void validate(SettingsRequest r) {
        boolean ok = r.slotMinutes() != null && r.slotMinutes() >= 10 && r.slotMinutes() <= 240 && r.slotMinutes() % 10 == 0
                && r.capacityPerSlot() != null && r.capacityPerSlot() >= 1 && r.capacityPerSlot() <= 50
                && (r.maxPartySize() == null || (r.maxPartySize() >= 1 && r.maxPartySize() <= 100))
                && r.bookingWindowDays() != null && r.bookingWindowDays() >= 1 && r.bookingWindowDays() <= 60
                // 취소 마감은 예약 시각 24시간 전보다 이르게 못 잡는다(FR-RSV-05 #1)
                && r.cancelDeadlineHours() != null && r.cancelDeadlineHours() >= 0 && r.cancelDeadlineHours() <= 24
                && (r.lateCancelRefundRate() == null
                        || (r.lateCancelRefundRate().signum() >= 0 && r.lateCancelRefundRate().compareTo(BigDecimal.ONE) <= 0
                            && r.lateCancelRefundRate().scale() <= 3))
                && r.depositPerPerson() != null && r.depositPerPerson() >= 0 && r.depositPerPerson() <= 1_000_000;
        if (!ok) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }

    /** 모든 칸을 보낸다(부분 수정 없음). 비울 수 있는 칸: maxPartySize(제한 없음) · lateCancelRefundRate(권역 기본값) */
    record SettingsRequest(
            Integer slotMinutes,
            Integer capacityPerSlot,
            Integer maxPartySize,
            Integer bookingWindowDays,
            Integer cancelDeadlineHours,
            BigDecimal lateCancelRefundRate,
            Integer depositPerPerson) {
    }

    record SettingsResponse(
            int slotMinutes,
            int capacityPerSlot,
            Integer maxPartySize,
            int bookingWindowDays,
            int cancelDeadlineHours,
            BigDecimal lateCancelRefundRate,
            BigDecimal effectiveLateCancelRefundRate,
            int depositPerPerson) {
    }
}
