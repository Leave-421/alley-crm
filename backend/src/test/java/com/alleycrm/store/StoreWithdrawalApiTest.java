package com.alleycrm.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** C7 가게 탈퇴: 사장님 신청 → 상인회가 미정산 확인 후 처리 (FR-STO-03). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StoreWithdrawalApiTest {

    static final String PASSWORD = "password1";
    /** 2026-12-01(화) 09:00 KST */
    static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");
    static final String WEDNESDAY = "2026-12-02";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountService accountService;
    @Autowired
    AppClock clock;

    long regionId;
    long otherRegionId;
    long storeId;
    long senderStoreId;
    long customerId;
    String ownerLogin;
    String associationLogin;
    String otherAssociationLogin;
    String customerLogin;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        jdbc.update("INSERT INTO region (name) VALUES ('탈퇴 테스트 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO region (name) VALUES ('탈퇴 테스트 다른 권역')");
        otherRegionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeId = store("approved");
        senderStoreId = store("approved");
        ownerLogin = "wdowner" + System.nanoTime();
        accountService.createOwner(storeId, ownerLogin, PASSWORD);
        associationLogin = "wdassoc" + System.nanoTime();
        accountService.createAssociation(regionId, associationLogin, PASSWORD);
        otherAssociationLogin = "wdassoc" + System.nanoTime();
        accountService.createAssociation(otherRegionId, otherAssociationLogin, PASSWORD);
        customerLogin = "wdcustomer" + System.nanoTime();
        long accountId = accountService.signupCustomer(customerLogin, PASSWORD, "회원", null, null).id();
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM referral_fee WHERE receiving_store_id = ?", storeId);
        jdbc.update("DELETE FROM settlement WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM coupon WHERE target_store_id = ?", storeId);
        jdbc.update("DELETE FROM coupon_policy WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM customer WHERE id = ?", customerId);
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'wd%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id IN (?, ?)", regionId, otherRegionId);
    }

    @Test
    void FR_STO_03_3_탈퇴를_신청해도_처리_전까지_가게는_그대로_운영된다() throws Exception {
        requestWithdrawal(login(ownerLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("withdraw_requested"));

        mvc.perform(get("/api/v1/owner/store").session(login(ownerLogin))).andExpect(status().isOk());
        stores().andExpect(jsonPath("$.items[*].id").value(Matchers.hasItem(String.valueOf(storeId))));
        slots().andExpect(status().isOk()).andExpect(jsonPath("$.acceptingReservations").value(true));
        mvc.perform(get("/api/v1/store-joins").param("status", "withdraw_requested").session(login(associationLogin)))
                .andExpect(jsonPath("$.items[*].storeId").value(Matchers.contains(String.valueOf(storeId))));
    }

    @Test
    void FR_STO_03_1_미정산_수수료가_있으면_신청도_처리도_거절() throws Exception {
        referralFee("2026-11");

        requestWithdrawal(login(ownerLogin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        assertThat(joinStatus()).isEqualTo("approved");

        // 신청 뒤에 생긴 미정산도 처리할 때 다시 확인한다
        jdbc.update("UPDATE store SET join_status = 'withdraw_requested' WHERE id = ?", storeId);
        withdraw(login(associationLogin)).andExpect(status().isConflict());
        assertThat(joinStatus()).isEqualTo("withdraw_requested");

        // 상인회가 그 달 정산을 확정하면 처리된다(집계만 된 달은 미정산)
        jdbc.update("INSERT INTO settlement (region_id, month, status) VALUES (?, '2026-11', 'aggregated')", regionId);
        withdraw(login(associationLogin)).andExpect(status().isConflict());
        jdbc.update("UPDATE settlement SET status = 'confirmed', confirmed_at = ? WHERE region_id = ?", utc(NOW), regionId);
        withdraw(login(associationLogin)).andExpect(status().isOk());
    }

    @Test
    void FR_STO_03_2_정산을_마친_가게를_처리하면_목록에서_빠지고_미사용_쿠폰은_적용_취소() throws Exception {
        referralFee("2026-11");
        jdbc.update("INSERT INTO settlement (region_id, month, status, confirmed_at) VALUES (?, '2026-11', 'confirmed', ?)",
                regionId, utc(NOW));
        long downloaded = coupon("downloaded");
        long used = coupon("used");
        requestWithdrawal(login(ownerLogin)).andExpect(status().isOk());

        withdraw(login(associationLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("withdrawn"));

        stores().andExpect(jsonPath("$.items[*].id").value(Matchers.not(Matchers.hasItem(String.valueOf(storeId)))));
        mvc.perform(get("/api/v1/stores/{id}/available-slots", storeId).param("date", WEDNESDAY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/owner/store").session(login(ownerLogin))).andExpect(status().isForbidden());
        assertThat(couponStatus(downloaded)).isEqualTo("apply_canceled");
        assertThat(couponStatus(used)).isEqualTo("used");
        // 30일 제한 해제: 이 시각 전 다운로드는 제한 계산에서 빠진다(CouponLimitRepository)
        assertThat(jdbc.queryForObject("SELECT business_status_changed_at FROM store WHERE id = ?", LocalDateTime.class, storeId)
                .toInstant(ZoneOffset.UTC)).isBetween(NOW, NOW.plusSeconds(60));
        // 방문 이력은 집계용으로 남는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE receiving_store_id = ?", Integer.class, storeId))
                .isEqualTo(1);
    }

    @Test
    void 상태가_맞지_않으면_409_다른_권역은_404_상인회만_처리한다() throws Exception {
        withdraw(login(associationLogin)).andExpect(status().isConflict()); // 신청 전
        requestWithdrawal(login(ownerLogin)).andExpect(status().isOk());
        requestWithdrawal(login(ownerLogin)).andExpect(status().isConflict()); // 이미 신청함

        withdraw(login(otherAssociationLogin)).andExpect(status().isNotFound());
        withdraw(login(ownerLogin)).andExpect(status().isForbidden());
        requestWithdrawal(login(customerLogin)).andExpect(status().isForbidden());
        assertThat(joinStatus()).isEqualTo("withdraw_requested");
    }

    @Test
    void 로그인_안_하면_401_CSRF_없으면_403() throws Exception {
        mvc.perform(post("/api/v1/owner/store/withdrawal").with(TestCsrf.csrf(mvc))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/owner/store/withdrawal").session(login(ownerLogin))).andExpect(status().isForbidden());
        assertThat(joinStatus()).isEqualTo("approved");
    }

    private long store(String joinStatus) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, closed_days)
                VALUES (?, '탈퇴 가게', 'cafe', 'center', ?, '10:00', '22:00', '')
                """, regionId, joinStatus);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void referralFee(String month) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, visited_at)
                VALUES (?, ?, ?, ?, 2, 'visited', ?, ?)
                """, "W" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(NOW.minusSeconds(86_400 * 10)),
                utc(NOW.minusSeconds(86_400 * 11)), utc(NOW.minusSeconds(86_400 * 10)));
        long reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, fee_base, fee_rate,
                                          fee_amount, settlement_month)
                VALUES (?, ?, ?, 30000, 30000, 0.0500, 1500, ?)
                """, reservationId, storeId, senderStoreId, month);
    }

    private long coupon(String status) {
        // 가게당 진행 중인 행사는 하나(uk_coupon_policy_active)라 있으면 그 행사를 쓴다
        List<Long> policies = jdbc.queryForList("SELECT id FROM coupon_policy WHERE store_id = ?", Long.class, storeId);
        if (policies.isEmpty()) {
            jdbc.update("""
                    INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, confirmed_at)
                    VALUES (?, 'fixed_discount', 1000, 10, ?)
                    """, storeId, utc(NOW));
        }
        long policyId = jdbc.queryForObject("SELECT id FROM coupon_policy WHERE store_id = ?", Long.class, storeId);
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, customerId, policyId, storeId, utc(NOW.minusSeconds(3600)), utc(NOW.plusSeconds(86_400)), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String couponStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, id);
    }

    private String joinStatus() {
        return jdbc.queryForObject("SELECT join_status FROM store WHERE id = ?", String.class, storeId);
    }

    private ResultActions stores() throws Exception {
        return mvc.perform(get("/api/v1/stores").param("regionId", String.valueOf(regionId))).andExpect(status().isOk());
    }

    private ResultActions slots() throws Exception {
        return mvc.perform(get("/api/v1/stores/{id}/available-slots", storeId).param("date", WEDNESDAY));
    }

    private ResultActions requestWithdrawal(MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/v1/owner/store/withdrawal").session(session).with(TestCsrf.csrf(mvc)));
    }

    private ResultActions withdraw(MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/v1/store-joins/{id}/withdraw", storeId).session(session).with(TestCsrf.csrf(mvc)));
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
