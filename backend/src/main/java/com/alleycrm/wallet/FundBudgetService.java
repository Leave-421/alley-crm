package com.alleycrm.wallet;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상인회 쿠폰 행사의 공동 기금 예산(D20, 기획 11-25 (5)). 기금은 마이너스가 될 수 없다.
 * <ul>
 *   <li>{@link #reserve}: 행사를 만들 때(E18) 예산 = 수량 × 한 장 최대 할인액(정액은 할인 금액, 정률은 최대 할인액)을 기금에서 미리 뺀다.
 *       기금이 모자라면 {@code insufficient_balance} → 행사 생성도 되돌아간다</li>
 *   <li>{@link #releaseDue}: 유효기간(valid_end_at)이 끝난 행사의 쓰지 않은 예산을 기금으로 돌려준다(중단한 행사도 같다).
 *       D7 스케줄이 주기적으로 부른다</li>
 * </ul>
 * 쓴 예산 = 그 행사 쿠폰 중 예약에 적용 · 사용된 것의 실제 할인액(방문 전이면 한 장 최대 할인액으로 잡아 둔다).
 */
@Service
public class FundBudgetService {

    private final JdbcTemplate jdbc;
    private final WalletService wallets;
    private final Clock clock;

    public FundBudgetService(JdbcTemplate jdbc, WalletService wallets, Clock clock) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.clock = clock;
    }

    /** 예산을 잡고 행사의 reserved_budget에 남긴다. 같은 행사에 두 번 잡지 않는다 */
    @Transactional
    public long reserve(long eventId) {
        Map<String, Object> e = jdbc.queryForMap("""
                SELECT region_id, total_quantity, benefit_type, discount_amount, max_discount_amount
                FROM association_coupon_event WHERE id = ? FOR UPDATE
                """, eventId);
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_tx WHERE association_event_id = ? AND type = ?
                """, Integer.class, eventId, WalletTxType.FUND_BUDGET_RESERVED);
        if (already != null && already > 0) {
            throw new IllegalStateException("이미 예산을 잡은 행사: " + eventId);
        }
        long budget = (long) ((Number) e.get("total_quantity")).intValue() * perCoupon(e);
        jdbc.update("UPDATE association_coupon_event SET reserved_budget = ? WHERE id = ?", budget, eventId);
        if (budget > 0) {
            wallets.post(WalletOwner.fund(((Number) e.get("region_id")).longValue()), WalletTxType.FUND_BUDGET_RESERVED,
                    -budget, null, eventId);
        }
        return budget;
    }

    /** @return 예산을 돌려준 행사 수 */
    @Transactional
    public int releaseDue() {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM association_coupon_event
                WHERE valid_end_at <= ? AND budget_released_at IS NULL ORDER BY id
                """, Long.class, now);
        int n = 0;
        for (Long id : ids) {
            Map<String, Object> e = jdbc.queryForMap("""
                    SELECT region_id, reserved_budget, benefit_type, discount_amount, max_discount_amount, budget_released_at
                    FROM association_coupon_event WHERE id = ? FOR UPDATE
                    """, id);
            if (e.get("budget_released_at") != null) {
                continue;
            }
            Long used = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(COALESCE(discount_applied, ?)), 0) FROM coupon
                    WHERE association_event_id = ? AND status IN ('applied', 'used')
                    """, Long.class, perCoupon(e), id);
            long unused = ((Number) e.get("reserved_budget")).longValue() - (used == null ? 0 : used);
            if (unused > 0) {
                wallets.post(WalletOwner.fund(((Number) e.get("region_id")).longValue()), WalletTxType.FUND_BUDGET_RELEASED,
                        unused, null, id);
            }
            jdbc.update("UPDATE association_coupon_event SET budget_released_at = ? WHERE id = ?", now, id);
            n++;
        }
        return n;
    }

    /** 한 장 최대 할인액: 정액은 할인 금액, 정률은 최대 할인액(상인회 쿠폰은 필수) */
    private static long perCoupon(Map<String, Object> e) {
        Object v = "fixed_discount".equals(e.get("benefit_type")) ? e.get("discount_amount") : e.get("max_discount_amount");
        if (v == null) {
            throw new ApiException(ErrorCode.INVALID);
        }
        return ((Number) v).longValue();
    }
}
