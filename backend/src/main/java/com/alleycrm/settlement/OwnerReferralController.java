package com.alleycrm.settlement;

import static com.alleycrm.common.time.ServiceTime.instant;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.reservation.owner.NameMask;
import com.alleycrm.reservation.owner.OwnerStoreResolver;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사장님 소개 수수료 내역(D16, FR-STL-03, O-08 · O-15)과 소개 실적(FR-STL-04).
 * 보낸 가게 id · 이름은 어떤 응답에도 넣지 않는다(FR-CUS-05).
 */
@RestController
class OwnerReferralController {

    private final OwnerStoreResolver owners;
    private final JdbcTemplate jdbc;
    private final SettingService settings;

    OwnerReferralController(OwnerStoreResolver owners, JdbcTemplate jdbc, SettingService settings) {
        this.owners = owners;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** 내 가게가 낸 그 달(정산 달 = 방문 처리된 달) 수수료 */
    @GetMapping("/api/v1/owner/referral-fees")
    Map<String, Object> fees(@RequestParam String month, Authentication auth) {
        String m = month(month);
        long storeId = owners.storeIdOf(auth);
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        List<FeeItem> items = jdbc.query("""
                SELECT f.reservation_id, r.reservation_no, c.name, r.visited_at, f.visit_amount, f.coupon_discount,
                       f.fee_base, f.fee_rate, f.fee_amount, f.coupon_cost,
                       (SELECT COALESCE(SUM(x.fee_difference), 0) FROM visit_amount_correction x
                        WHERE x.reservation_id = f.reservation_id) AS fee_difference
                FROM referral_fee f
                JOIN reservation r ON r.id = f.reservation_id
                JOIN customer c ON c.id = r.customer_id
                WHERE f.receiving_store_id = ? AND f.settlement_month = ?
                ORDER BY r.visited_at, f.id
                """, (rs, i) -> new FeeItem(
                String.valueOf(rs.getLong("reservation_id")),
                rs.getString("reservation_no"),
                NameMask.mask(rs.getString("name")),
                instant(rs.getObject("visited_at", LocalDateTime.class)),
                rs.getInt("visit_amount"),
                rs.getInt("coupon_discount"),
                rs.getInt("fee_base"),
                rs.getBigDecimal("fee_rate"),
                rs.getInt("fee_amount"),
                rs.getInt("coupon_cost"),
                rs.getInt("fee_difference")), storeId, m);
        List<String> status = jdbc.queryForList("SELECT status FROM settlement WHERE region_id = ? AND month = ?",
                String.class, regionId, m);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", m);
        // 월 정산(D17)이 아직 없으면 null = 잠정
        out.put("settlementStatus", status.isEmpty() ? null : status.get(0));
        out.put("referralFeeRate", settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE));
        out.put("count", items.size());
        out.put("totalFee", items.stream().mapToLong(i -> i.feeAmount() + i.feeDifference()).sum());
        out.put("totalCouponCost", items.stream().mapToLong(FeeItem::couponCost).sum());
        out.put("items", items);
        return out;
    }

    /**
     * 소개 실적: 보낸 손님 = 내 가게 예약의 제안으로 확정된 소개(내 예약이 취소 · 미방문이어도 셈),
     * 받은 손님 = 내 가게가 받은 확정 소개. month가 없으면 전체 기간.
     */
    @GetMapping("/api/v1/owner/referral-stats")
    Map<String, Object> stats(@RequestParam(required = false) String month, Authentication auth) {
        long storeId = owners.storeIdOf(auth);
        String m = month == null ? null : month(month);
        String where = m == null ? "" : " AND settlement_month = ?";
        Object[] sentArgs = m == null ? new Object[] {storeId} : new Object[] {storeId, m};
        Integer sent = jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE sender_store_id = ?" + where,
                Integer.class, sentArgs);
        Integer received = jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE receiving_store_id = ?" + where,
                Integer.class, sentArgs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", m);
        out.put("sentCount", sent);
        out.put("receivedCount", received);
        return out;
    }

    /** 수수료 내역 한 줄(O-15). 손님은 가린 이름만, 보낸 가게 없음 */
    record FeeItem(
            String reservationId,
            String reservationNo,
            String maskedName,
            Instant visitedAt,
            int visitAmount,
            int couponDiscount,
            int feeBase,
            BigDecimal feeRate,
            int feeAmount,
            int couponCost,
            int feeDifference) {
    }

    private static String month(String value) {
        try {
            return YearMonth.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID);
        }
    }
}
