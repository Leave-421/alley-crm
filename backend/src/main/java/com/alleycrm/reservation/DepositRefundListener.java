package com.alleycrm.reservation;

import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 예약금 환불(D10). 상태를 바꾼 트랜잭션 안에서 처리해, 환불이 실패하면 상태 변경도 되돌아간다.
 * <ul>
 *   <li>가게 거절 · 자동 거절(store_rejected): 100% 환불(FR-RSV-03 #2 · #3)</li>
 *   <li>자동 취소(auto_canceled): 환불 없음(FR-RSV-02 #1)</li>
 *   <li>손님 취소(customer_canceled): 취소 때 정한 refund_amount만큼(D6, FR-RSV-06)</li>
 * </ul>
 * 낸 예약금이 없거나 이미 환불했으면 아무것도 하지 않는다.
 */
@Component
class DepositRefundListener {

    private final WalletService wallets;
    private final JdbcTemplate jdbc;

    DepositRefundListener(WalletService wallets, JdbcTemplate jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(ReservationStatusChangedEvent e) {
        if (!ReservationStatus.CANCELED.equals(e.toStatus())) {
            return;
        }
        if (CancelReason.STORE_REJECTED.equals(e.cancelReason())) {
            refund(e.reservationId(), e.customerId(), Long.MAX_VALUE);
        } else if (CancelReason.CUSTOMER_CANCELED.equals(e.cancelReason())) {
            // 손님 취소(D6): 취소할 때 정한 금액만큼(FR-RSV-06)
            Integer amount = jdbc.queryForObject("SELECT refund_amount FROM reservation WHERE id = ?", Integer.class, e.reservationId());
            refund(e.reservationId(), e.customerId(), amount == null ? 0 : amount);
        }
    }

    private void refund(long reservationId, long customerId, long max) {
        // 이 예약에 남은 예약금 = 낸 금액 − 돌려준 금액(거래 합). 두 번 불려도 한 번만 돌려준다
        Long net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM wallet_tx WHERE reservation_id = ? AND type IN (?, ?)
                """, Long.class, reservationId, WalletTxType.DEPOSIT_PAID, WalletTxType.DEPOSIT_REFUNDED);
        long refund = Math.min(net == null ? 0 : -net, max);
        if (refund <= 0) {
            return;
        }
        List<Map<String, Object>> c = jdbc.queryForList("SELECT type, phone FROM customer WHERE id = ?", customerId);
        if (!c.isEmpty() && "member".equals(c.get(0).get("type"))) {
            wallets.post(WalletOwner.customer(customerId), WalletTxType.DEPOSIT_REFUNDED, refund, reservationId);
        } else {
            String phone = c.isEmpty() ? null : (String) c.get(0).get("phone");
            wallets.recordGuest(phone, WalletTxType.DEPOSIT_REFUNDED, refund, reservationId);
        }
    }
}
