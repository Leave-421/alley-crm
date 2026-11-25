package com.alleycrm.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** D9 가상 잔액 · 거래 내역: FR-STL-06 #1, NFR-02 #1, 기획 11-25 (3) 가게 잔액 마이너스. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WalletTest {

    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    WalletService wallets;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    MockMvc mvc;

    long regionId;
    long storeId;
    long customerId;
    WalletOwner member;
    WalletOwner store;
    WalletOwner fund;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        regionId = insert("INSERT INTO region (name) VALUES ('테스트 권역')");
        storeId = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + regionId + ", 'B', 'cafe', 'center', 'approved')");
        long memberAccount = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('member1', 'x', 'customer')");
        customerId = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + memberAccount + ")");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", storeId);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, region_id) VALUES ('assoc1', 'x', 'association', ?)", regionId);
        member = WalletOwner.customer(customerId);
        store = WalletOwner.store(storeId);
        fund = WalletOwner.fund(regionId);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"wallet_tx", "wallet", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void 충전하면_잔액이_늘고_거래_내역이_남는다() {
        assertThat(wallets.post(member, WalletTxType.CHARGE, 30_000, null)).isEqualTo(30_000);

        assertThat(wallets.transactions(member, 0, 20)).singleElement()
                .satisfies(t -> {
                    assertThat(t.type()).isEqualTo("charge");
                    assertThat(t.amount()).isEqualTo(30_000);
                    // 거래 시각은 서버 시계(D18)를 따른다
                    assertThat(t.createdAt()).isBetween(NOW, NOW.plusSeconds(5));
                });
    }

    @Test
    void 회원_잔액은_마이너스가_되지_않는다() {
        wallets.post(member, WalletTxType.CHARGE, 20_000, null);

        assertThatThrownBy(() -> wallets.post(member, WalletTxType.DEPOSIT_PAID, -30_000, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        assertThat(wallets.balance(member)).isEqualTo(20_000);
        assertThat(wallets.countTransactions(member)).isEqualTo(1);
    }

    @Test
    void 가게_잔액보다_큰_수수료도_빠지고_잔액이_마이너스가_된다() {
        wallets.post(store, WalletTxType.CHARGE, 1_000, null);

        wallets.transfer(store, WalletTxType.REFERRAL_FEE_CHARGED, fund, WalletTxType.FUND_DEPOSITED, 1_200, null);

        assertThat(wallets.balance(store)).isEqualTo(-200);
        assertThat(wallets.balance(fund)).isEqualTo(1_200);
        assertThat(wallets.transactions(store, 0, 20)).extracting(WalletService.WalletTx::type)
                .containsExactlyInAnyOrder("charge", "referral_fee_charged");
        assertThat(wallets.transactions(fund, 0, 20)).extracting(WalletService.WalletTx::type)
                .containsExactly("fund_deposited");
    }

    @Test
    void FR_STL_06_1_모든_잔액_변동에_거래_내역이_있다() {
        wallets.post(member, WalletTxType.CHARGE, 50_000, null);
        wallets.post(member, WalletTxType.DEPOSIT_PAID, -30_000, null);
        wallets.post(member, WalletTxType.DEPOSIT_REFUNDED, 15_000, null);
        wallets.transfer(store, WalletTxType.REFERRAL_FEE_CHARGED, fund, WalletTxType.FUND_DEPOSITED, 1_200, null);

        // NFR-02: 돈은 장부에만 있다. 지갑마다 잔액 = 거래 내역 합
        Integer mismatched = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet w
                WHERE w.balance <> (SELECT COALESCE(SUM(t.amount), 0) FROM wallet_tx t WHERE t.wallet_id = w.id)
                """, Integer.class);
        assertThat(mismatched).isZero();
        assertThat(wallets.balance(member)).isEqualTo(35_000);
    }

    @Test
    void 비회원은_지갑_없이_전화번호에_묶인_기록만_남는다() {
        long r = reservation();
        wallets.recordGuest("01012345678", WalletTxType.DEPOSIT_PAID, -10_000, r);
        wallets.recordGuest("01012345678", WalletTxType.DEPOSIT_REFUNDED, 10_000, r);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_tx WHERE guest_phone = '01012345678'", Integer.class)).isEqualTo(2);
        jdbc.update("DELETE FROM wallet_tx");
        jdbc.update("DELETE FROM reservation");
    }

    @Test
    @WithMockUser(username = "owner1")
    void 사장님은_가게_잔액을_보고_마이너스면_충전_필요() throws Exception {
        wallets.transfer(store, WalletTxType.REFERRAL_FEE_CHARGED, fund, WalletTxType.FUND_DEPOSITED, 1_200, null);

        mvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("store"))
                .andExpect(jsonPath("$.balance").value(-1200))
                .andExpect(jsonPath("$.needsCharge").value(true));
        mvc.perform(get("/api/v1/wallets/me/transactions"))
                .andExpect(jsonPath("$.items[0].type").value("referral_fee_charged"))
                .andExpect(jsonPath("$.items[0].amount").value(-1200))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(username = "member1")
    void 회원은_자기_잔액만_본다() throws Exception {
        wallets.post(member, WalletTxType.CHARGE, 30_000, null);
        wallets.post(store, WalletTxType.CHARGE, 99_000, null);

        mvc.perform(get("/api/v1/wallets/me"))
                .andExpect(jsonPath("$.ownerType").value("customer"))
                .andExpect(jsonPath("$.balance").value(30000))
                .andExpect(jsonPath("$.needsCharge").value(false));
    }

    @Test
    @WithMockUser(username = "assoc1")
    void 상인회는_공동_기금을_본다() throws Exception {
        wallets.transfer(store, WalletTxType.REFERRAL_FEE_CHARGED, fund, WalletTxType.FUND_DEPOSITED, 1_200, null);

        mvc.perform(get("/api/v1/wallets/me"))
                .andExpect(jsonPath("$.ownerType").value("fund"))
                .andExpect(jsonPath("$.balance").value(1200));
    }

    @Test
    void 로그인_안_하면_401() throws Exception {
        mvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
    }

    private long reservation() {
        long c = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님', '01012345678')");
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at) VALUES ('W-1', "
                + storeId + ", " + c + ", NOW(6), 2, NOW(6))");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
