package com.alleycrm.reservation.request;

import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 85 예약금 납부 기록 (D10, FR-RSV-08). 저장(80) 뒤라 거래에 예약 id가 남는다.
 * <ul>
 *   <li>회원: 가상 잔액에서 뺀다. 모자라면 insufficient_balance → 신청 전체가 되돌아간다(#5)</li>
 *   <li>비회원: 지갑 없이 전화번호에 묶은 '예약금 납부' 기록만(#4)</li>
 *   <li>예약금 0원: 기록하지 않는다(#1)</li>
 * </ul>
 */
@Component
class DepositPaymentStep implements ReservationRequestStep {

    private final WalletService wallets;
    private final JdbcTemplate jdbc;

    DepositPaymentStep(WalletService wallets, JdbcTemplate jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return DEPOSIT_PAYMENT;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        int amount = ctx.getDepositAmount();
        if (amount == 0) {
            return;
        }
        long reservationId = ctx.getReservation().getId();
        Map<String, Object> c = jdbc.queryForMap("SELECT type, phone FROM customer WHERE id = ?", ctx.getCustomerId());
        if ("member".equals(c.get("type"))) {
            wallets.post(WalletOwner.customer(ctx.getCustomerId()), WalletTxType.DEPOSIT_PAID, -amount, reservationId);
        } else {
            wallets.recordGuest((String) c.get("phone"), WalletTxType.DEPOSIT_PAID, -amount, reservationId);
        }
    }
}
