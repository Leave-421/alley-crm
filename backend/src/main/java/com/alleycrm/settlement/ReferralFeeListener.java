package com.alleycrm.settlement;

import com.alleycrm.common.time.ServiceTime;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.service.CouponVisitService;
import com.alleycrm.reservation.VisitProcessedEvent;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 소개 수수료(D15, FR-STL-02). '방문 처리됨'(D11)을 방문 처리한 트랜잭션 안에서 받는다. 실패하면 방문 처리도 되돌아간다.
 * <ul>
 *   <li>소개 후보(제안 id · 보낸 가게가 있는 예약, D12)만. 소개 확정은 예약 건당 한 번(uk_referral_fee_reservation)</li>
 *   <li>수수료 계산 금액 = 방문 금액 − 실제 쿠폰 할인(E14 discountFor). 서비스 제공 쿠폰은 할인 0</li>
 *   <li>수수료 = 계산 금액 × 수수료율(설정값, 생길 때 값을 기록), 1원 미만 버림</li>
 *   <li>받은 가게 잔액 → 권역 공동 기금. 가게 잔액이 모자라도 빼고 마이너스가 될 수 있다(기획 11-25 (4))</li>
 *   <li>쿠폰 비용은 수수료와 따로 기록한다. 가게 쿠폰만(상인회 쿠폰은 기금이 낸다, 11-25 (5))</li>
 *   <li>정산 달 = 방문 처리된 달(운영 시간대). 늦은 방문 처리(D23)는 원래 예약 달(이벤트의 settlementAt)</li>
 * </ul>
 */
@Component
class ReferralFeeListener {

    private final JdbcTemplate jdbc;
    private final SettingService settings;
    private final WalletService wallets;
    private final CouponVisitService couponVisit;

    ReferralFeeListener(JdbcTemplate jdbc, SettingService settings, WalletService wallets, CouponVisitService couponVisit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.wallets = wallets;
        this.couponVisit = couponVisit;
    }

    @EventListener
    void on(VisitProcessedEvent e) {
        if (e.courseProposalId() == null || e.senderStoreId() == null) {
            return; // 소개가 아닌 예약(FR-STL-02 #4)
        }
        Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE reservation_id = ?",
                Integer.class, e.reservationId());
        if (already != null && already > 0) {
            return; // 건당 한 번(FR-STL-01 #8)
        }
        long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, e.storeId());
        BigDecimal rate = settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE);

        Optional<VisitCouponDiscount> coupon = couponVisit.discountFor(e.reservationId(), e.visitAmount());
        int discount = coupon.map(VisitCouponDiscount::discountAmount).orElse(0);
        int couponCost = coupon.filter(c -> c.issuerKind() == CouponIssuer.STORE)
                .map(VisitCouponDiscount::couponCost).orElse(0);
        int base = Math.max(0, e.visitAmount() - discount);
        int fee = rate.multiply(BigDecimal.valueOf(base)).setScale(0, RoundingMode.DOWN).intValueExact();
        String month = YearMonth.from(e.settlementAt().atZone(ServiceTime.ZONE)).toString();

        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, coupon_discount,
                                          fee_base, fee_rate, fee_amount, coupon_cost, settlement_month, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.reservationId(), e.storeId(), e.senderStoreId(), e.visitAmount(), discount,
                base, rate, fee, couponCost, month, LocalDateTime.ofInstant(e.visitedAt(), ZoneOffset.UTC));
        if (fee > 0) {
            wallets.transfer(WalletOwner.store(e.storeId()), WalletTxType.REFERRAL_FEE_CHARGED,
                    WalletOwner.fund(regionId), WalletTxType.FUND_DEPOSITED, fee, e.reservationId());
        }
    }
}
