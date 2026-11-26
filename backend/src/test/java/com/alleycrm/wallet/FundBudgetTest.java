package com.alleycrm.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** D20 공동 기금 예산 잡기 · 해제(FR-CPN-12, 기획 11-25 (5)): 기금은 마이너스가 될 수 없다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FundBudgetTest {

    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    FundBudgetService budgets;
    @Autowired
    WalletService wallets;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;

    long region;
    WalletOwner fund;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('권역')");
        fund = WalletOwner.fund(region);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"coupon", "wallet_tx", "wallet", "association_coupon_event", "customer", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void 예산은_수량_곱하기_한_장_최대_할인액_만큼_기금에서_빠진다() {
        wallets.post(fund, WalletTxType.FUND_DEPOSITED, 100_000, null);
        long event = event("percent_discount", null, 20, 5000, 10);

        assertThat(budgets.reserve(event)).isEqualTo(50_000);

        assertThat(wallets.balance(fund)).isEqualTo(50_000);
        assertThat(jdbc.queryForObject("SELECT reserved_budget FROM association_coupon_event WHERE id = ?", Long.class, event)).isEqualTo(50_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_tx WHERE type = 'fund_budget_reserved' AND association_event_id = ?",
                Integer.class, event)).isEqualTo(1);
    }

    @Test
    void 기금이_모자라면_거절하고_기금은_마이너스가_되지_않는다() {
        wallets.post(fund, WalletTxType.FUND_DEPOSITED, 30_000, null);
        long event = event("fixed_discount", 5000, null, null, 10);

        assertThatThrownBy(() -> budgets.reserve(event))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        assertThat(wallets.balance(fund)).isEqualTo(30_000);
    }

    @Test
    void 가게만_마이너스가_될_수_있다() {
        assertThat(WalletOwner.store(1).mayGoNegative()).isTrue();
        assertThat(WalletOwner.fund(1).mayGoNegative()).isFalse();
        assertThat(WalletOwner.customer(1).mayGoNegative()).isFalse();
    }

    @Test
    void 유효기간이_끝나면_쓰지_않은_예산을_돌려주고_한_번만() {
        wallets.post(fund, WalletTxType.FUND_DEPOSITED, 100_000, null);
        long event = event("fixed_discount", 5000, null, null, 10);
        budgets.reserve(event); // 50,000
        long member = insert("INSERT INTO customer (type) VALUES ('member')");
        long member2 = insert("INSERT INTO customer (type) VALUES ('member')");
        long member3 = insert("INSERT INTO customer (type) VALUES ('member')");
        coupon(member, event, "used", 3000);      // 실제 3,000 깎음
        coupon(member2, event, "applied", null);  // 방문 전: 한 장 최대 5,000으로 잡아 둠
        coupon(member3, event, "expired", null);  // 안 씀

        assertThat(budgets.releaseDue()).isZero(); // 유효기간 전
        clock.setTo(Instant.parse("2026-12-10T00:00:00Z"));
        assertThat(budgets.releaseDue()).isEqualTo(1);

        assertThat(wallets.balance(fund)).isEqualTo(50_000 + 42_000);
        assertThat(budgets.releaseDue()).isZero();
    }

    private long event(String type, Integer amount, Integer rate, Integer max, int qty) {
        jdbc.update("""
                INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_amount, discount_rate, max_discount_amount,
                    total_quantity, eligible_store_scope, issue_start_at, issue_end_at, valid_start_at, valid_end_at, reserved_budget, confirmed_at)
                VALUES (?, '행사', ?, ?, ?, ?, ?, 'all', '2026-11-27 00:00:00', '2026-11-30 00:00:00', '2026-11-27 00:00:00', '2026-12-05 00:00:00', 0, NOW(6))
                """, region, type, amount, rate, max, qty);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void coupon(long customer, long event, String status, Integer discount) {
        jdbc.update("""
                INSERT INTO coupon (customer_id, issuer_kind, association_event_id, downloaded_at, expires_at, status, discount_applied)
                VALUES (?, 'association', ?, NOW(6), '2026-12-05 00:00:00', ?, ?)
                """, customer, event, status, discount);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
