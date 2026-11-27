package com.alleycrm.settlement;

import static com.alleycrm.common.time.ServiceTime.instant;
import static com.alleycrm.common.time.ServiceTime.utc;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.ServiceTime;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.service.CouponVisitService;
import com.alleycrm.reservation.ReservationStatus;
import com.alleycrm.visitreport.VisitReport;
import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 금액 정정(D22, FR-STL-09 #1 · #3 · #4, 11-25 (5) · 11-27 (2)).
 * <ul>
 *   <li>방문 처리된 예약에 상인회가 '맞음'으로 기록한, 아직 정정에 쓰지 않은 금액 신고가 있을 때만. 신고 한 건에 정정 한 번</li>
 *   <li>원래 방문 금액 · 수수료 기록(reservation · referral_fee)은 고치지 않고 visit_amount_correction을 더한다</li>
 *   <li>소개 예약이면 수수료를 원래 수수료율로 다시 계산해(쿠폰 할인은 정정 금액 기준) 지금까지 낸 수수료와의 차액을 옮긴다.
 *       늘면 가게 → 공동 기금, 줄면 공동 기금 → 가게(11-27 (2)). 기금은 마이너스가 될 수 없어 모자라면 거절</li>
 *   <li>정산 달 = 원래 방문(예약) 달: 수수료 행의 정산 달, 수수료가 없으면 예약 달</li>
 * </ul>
 */
@Service
public class VisitAmountCorrectionService {

    static final int MAX_AMOUNT = 10_000_000;
    /** visit_report.result '맞음'(CONTRACTS 「C23」). VisitReport.RESULT_VALID는 패키지 전용이다 */
    private static final String RESULT_VALID = "valid";

    private final JdbcTemplate jdbc;
    private final WalletService wallets;
    private final CouponVisitService couponVisit;
    private final Clock clock;

    VisitAmountCorrectionService(JdbcTemplate jdbc, WalletService wallets, CouponVisitService couponVisit, Clock clock) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.couponVisit = couponVisit;
        this.clock = clock;
    }

    @Transactional
    public Correction correct(long storeId, long reservationId, Integer correctedAmount) {
        if (correctedAmount == null || correctedAmount < 0 || correctedAmount > MAX_AMOUNT) {
            throw new ApiException(ErrorCode.INVALID);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT store_id, status, visit_amount, start_at FROM reservation WHERE id = ? FOR UPDATE", reservationId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("store_id")).longValue() != storeId) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);
        if (!ReservationStatus.VISITED.equals(r.get("status"))) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        List<Long> reports = jdbc.queryForList("""
                SELECT v.id FROM visit_report v LEFT JOIN visit_amount_correction c ON c.report_id = v.id
                WHERE v.reservation_id = ? AND v.type = ? AND v.result = ? AND c.id IS NULL
                ORDER BY v.id LIMIT 1
                """, Long.class, reservationId, VisitReport.TYPE_AMOUNT, RESULT_VALID);
        if (reports.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT); // #4: '맞음' 금액 신고가 없거나 이미 정정에 썼다
        }
        // 지금 금액 = 마지막 정정 금액, 정정이 없으면 방문 처리 금액
        List<Integer> last = jdbc.queryForList(
                "SELECT corrected_amount FROM visit_amount_correction WHERE reservation_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, reservationId);
        int original = last.isEmpty() ? ((Number) r.get("visit_amount")).intValue() : last.get(0);

        int difference = 0;
        String month;
        List<Map<String, Object>> fee = jdbc.queryForList(
                "SELECT fee_rate, fee_amount, settlement_month FROM referral_fee WHERE reservation_id = ?", reservationId);
        if (fee.isEmpty()) {
            month = YearMonth.from(instant((LocalDateTime) r.get("start_at")).atZone(ServiceTime.ZONE)).toString();
        } else {
            BigDecimal rate = (BigDecimal) fee.get(0).get("fee_rate");
            int discount = couponVisit.discountFor(reservationId, correctedAmount)
                    .map(VisitCouponDiscount::discountAmount).orElse(0);
            int newFee = rate.multiply(BigDecimal.valueOf(Math.max(0, correctedAmount - discount)))
                    .setScale(0, RoundingMode.DOWN).intValueExact();
            Integer paidDiffs = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(fee_difference), 0) FROM visit_amount_correction WHERE reservation_id = ?",
                    Integer.class, reservationId);
            difference = newFee - (((Number) fee.get(0).get("fee_amount")).intValue() + paidDiffs);
            month = (String) fee.get(0).get("settlement_month");
        }

        Long txId = difference == 0 ? null : moveDifference(storeId, reservationId, difference);
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO visit_amount_correction (reservation_id, report_id, original_amount, corrected_amount,
                                                     fee_difference, wallet_tx_id, settlement_month, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, reservationId, reports.get(0), original, correctedAmount, difference, txId, month, utc(now));
        return new Correction(String.valueOf(reservationId), original, correctedAmount, difference, month, now);
    }

    /** 차액을 옮기고 가게 쪽 거래 id를 돌려준다. 줄었으면 기금에서 먼저 빼 모자라면 거절(기금은 마이너스 불가) */
    private long moveDifference(long storeId, long reservationId, int difference) {
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        WalletOwner store = WalletOwner.store(storeId);
        WalletOwner fund = WalletOwner.fund(regionId);
        if (difference > 0) {
            wallets.post(store, WalletTxType.FEE_DIFFERENCE_CHARGED, -difference, reservationId);
            long txId = lastInsertId();
            wallets.post(fund, WalletTxType.FEE_DIFFERENCE_DEPOSITED, difference, reservationId);
            return txId;
        }
        wallets.post(fund, WalletTxType.FEE_DIFFERENCE_WITHDRAWN, difference, reservationId);
        wallets.post(store, WalletTxType.FEE_DIFFERENCE_REFUNDED, -difference, reservationId);
        return lastInsertId();
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 정정 결과. 원래 금액은 이 정정 직전 금액(앞선 정정이 있으면 그 정정 금액) */
    public record Correction(String reservationId, int originalAmount, int correctedAmount, int feeDifference,
            String settlementMonth, Instant createdAt) {
    }
}
