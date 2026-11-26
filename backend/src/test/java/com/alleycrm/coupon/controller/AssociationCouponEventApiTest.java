package com.alleycrm.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.wallet.FundBudgetService;
import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 상인회 쿠폰 행사(CONTRACTS_API 3.8, FR-CPN-12). 기금이 모자라 행사 생성이 되돌아가는지 보려고 테스트 트랜잭션을 쓰지 않고,
 * 이 테스트가 만든 권역의 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssociationCouponEventApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-11-30 12:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-11-30T03:00:00Z");
    private static final String ISSUE_START = "2026-11-30T02:00:00Z";
    private static final String ISSUE_END = "2026-12-07T15:00:00Z";
    private static final String VALID_START = "2026-11-30T02:00:00Z";
    private static final String VALID_END = "2026-12-14T15:00:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AppClock clock;

    @Autowired
    AccountService accounts;

    @Autowired
    WalletService wallets;

    @Autowired
    FundBudgetService budgets;

    long region;
    long otherRegion;
    long storeA;
    long storeB;
    long requestedStore;
    long otherRegionStore;
    MockHttpSession association;
    final List<Long> customers = new ArrayList<>();

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('상인회 쿠폰 권역')");
        otherRegion = insert("INSERT INTO region (name) VALUES ('다른 권역')");
        storeA = store(region, "A", "approved");
        storeB = store(region, "B", "approved");
        requestedStore = store(region, "신청 중", "requested");
        otherRegionStore = store(otherRegion, "D", "approved");
        String loginId = "a" + System.nanoTime();
        accounts.createAssociation(region, loginId, PASSWORD);
        association = login(loginId);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (long r : new long[] {region, otherRegion}) {
            jdbc.update("DELETE FROM coupon WHERE association_event_id IN (SELECT id FROM association_coupon_event WHERE region_id = ?)", r);
            jdbc.update("DELETE FROM wallet_tx WHERE wallet_id IN (SELECT id FROM wallet WHERE owner_type = 'fund' AND owner_id = ?)", r);
            jdbc.update("DELETE FROM association_coupon_event_store WHERE event_id IN (SELECT id FROM association_coupon_event WHERE region_id = ?)", r);
            jdbc.update("DELETE FROM association_coupon_event WHERE region_id = ?", r);
            jdbc.update("DELETE FROM wallet WHERE owner_type = 'fund' AND owner_id = ?", r);
            jdbc.update("DELETE FROM account WHERE region_id = ? OR store_id IN (SELECT id FROM store WHERE region_id = ?)", r, r);
            jdbc.update("DELETE FROM store WHERE region_id = ?", r);
            jdbc.update("DELETE FROM region WHERE id = ?", r);
        }
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
    }

    @Test
    void 정액_5000원_10장을_만들면_기금에서_50000원이_잡히고_거래가_남는다_FR_CPN_12_1() throws Exception {
        fund(100_000);

        String id = idOf(create(fixed(5000, 10)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.discountAmount").value(5000))
                .andExpect(jsonPath("$.maxDiscountAmount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.eligibleStoreScope").value("all"))
                .andExpect(jsonPath("$.storeIds").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(10))
                .andExpect(jsonPath("$.issuedCount").value(0))
                .andExpect(jsonPath("$.remainingQuantity").value(10))
                .andExpect(jsonPath("$.reservedBudget").value(50000))
                .andExpect(jsonPath("$.usedBudget").value(0))
                .andExpect(jsonPath("$.remainingBudget").value(50000))
                .andExpect(jsonPath("$.validEndAt").value(VALID_END))
                .andExpect(jsonPath("$.confirmedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.stoppedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.issuable").value(true)));

        assertThat(fundBalance()).isEqualTo(50_000);
        assertThat(jdbc.queryForObject("""
                SELECT amount FROM wallet_tx WHERE type = 'fund_budget_reserved' AND association_event_id = ?
                """, Long.class, Long.parseLong(id))).isEqualTo(-50_000);
        mvc.perform(get("/api/v1/association/coupon-events").session(association))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(id)))
                .andExpect(jsonPath("$.items[0].title").value("주말 할인"));
    }

    @Test
    void 기금보다_예산이_크면_거절하고_행사도_기금도_그대로_FR_CPN_12_2() throws Exception {
        fund(30_000);

        create(percent(10, 5000, 10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("insufficient_balance"));

        assertThat(eventCount()).isZero();
        assertThat(fundBalance()).isEqualTo(30_000);
    }

    @Test
    void 정률_할인은_최대_할인액이_없으면_거절_FR_CPN_12_3() throws Exception {
        fund(100_000);

        create(percent(10, null, 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        assertThat(eventCount()).isZero();
        assertThat(fundBalance()).isEqualTo(100_000);
    }

    @Test
    void 중단하면_새로_받기만_멈추고_받은_쿠폰과_예산은_그대로_FR_CPN_12_4() throws Exception {
        fund(100_000);
        long id = Long.parseLong(idOf(create(fixed(5000, 10)).andExpect(status().isCreated())));
        issue(id, 6, 3); // 6장 받음, 그중 3장 사용(5,000원씩)

        stop(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.stoppedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.issuable").value(false))
                .andExpect(jsonPath("$.issuedCount").value(6))
                .andExpect(jsonPath("$.usedBudget").value(15000))
                .andExpect(jsonPath("$.remainingBudget").value(35000))
                .andExpect(jsonPath("$.budgetReleasedAt").value(Matchers.nullValue()));

        assertThat(jdbc.queryForList("SELECT status FROM coupon WHERE association_event_id = ? ORDER BY id", String.class, id))
                .containsExactly("used", "used", "used", "downloaded", "downloaded", "downloaded");
        assertThat(fundBalance()).isEqualTo(50_000); // 중단 때는 돌아오지 않음
        stop(id).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 중단한_행사도_유효기간이_끝나면_쓰지_않은_예산이_기금으로_돌아온다_FR_CPN_12_5() throws Exception {
        fund(100_000);
        long id = Long.parseLong(idOf(create(fixed(5000, 10)).andExpect(status().isCreated())));
        issue(id, 6, 3);
        stop(id).andExpect(status().isOk());

        clock.setTo(Instant.parse(VALID_END).plusSeconds(60));
        budgets.releaseDue();

        assertThat(fundBalance()).isEqualTo(85_000); // 50,000 + (50,000 − 15,000)
        assertThat(jdbc.queryForObject("""
                SELECT amount FROM wallet_tx WHERE type = 'fund_budget_released' AND association_event_id = ?
                """, Long.class, id)).isEqualTo(35_000);
        mvc.perform(get("/api/v1/association/coupon-events").session(association))
                .andExpect(jsonPath("$.items[0].budgetReleasedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.items[0].issuable").value(false));
    }

    @Test
    void 고른_가게로_만들면_가게_ID_순으로_남는다() throws Exception {
        fund(100_000);
        Map<String, Object> body = percent(10, 3000, 5);
        body.put("eligibleStoreScope", "selected");
        body.put("storeIds", List.of(String.valueOf(storeB), String.valueOf(storeA)));

        String id = idOf(create(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.eligibleStoreScope").value("selected"))
                .andExpect(jsonPath("$.storeIds").value(Matchers.contains(String.valueOf(storeA), String.valueOf(storeB))))
                .andExpect(jsonPath("$.discountPercent").value(10))
                .andExpect(jsonPath("$.reservedBudget").value(15000)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM association_coupon_event_store WHERE event_id = ?",
                Integer.class, Long.parseLong(id))).isEqualTo(2);
    }

    @Test
    void 값이_틀리면_400이고_아무것도_만들지_않는다() throws Exception {
        fund(1_000_000);
        List<Map<String, Object>> bodies = new ArrayList<>();
        bodies.add(with(fixed(5000, 10), "benefitType", "service"));
        bodies.add(with(fixed(5000, 10), "discountAmount", null));
        bodies.add(with(percent(51, 5000, 10), "maxDiscountAmount", 5000));
        bodies.add(with(fixed(5000, 10), "totalQuantity", 0));
        bodies.add(with(fixed(5000, 10), "title", ""));
        bodies.add(with(fixed(5000, 10), "title", "가".repeat(51)));
        bodies.add(with(fixed(5000, 10), "eligibleStoreScope", "some"));
        bodies.add(selected(fixed(5000, 10)));
        bodies.add(selected(fixed(5000, 10), storeA, storeA));
        bodies.add(selected(fixed(5000, 10), storeA, requestedStore));
        bodies.add(selected(fixed(5000, 10), storeA, otherRegionStore));
        bodies.add(with(fixed(5000, 10), "issueEndAt", "2026-12-20T00:00:00Z")); // 쓸 수 있는 기간보다 늦게 끝남
        bodies.add(with(with(fixed(5000, 10), "issueStartAt", "2026-11-20T00:00:00Z"), "issueEndAt", "2026-11-30T02:59:00Z"));
        bodies.add(with(fixed(5000, 10), "validStartAt", VALID_END));
        bodies.add(with(fixed(5000, 10), "issueStartAt", "2026-12-01"));
        for (Map<String, Object> body : bodies) {
            create(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid"));
        }
        mvc.perform(post("/api/v1/association/coupon-events").session(association).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());

        assertThat(eventCount()).isZero();
        assertThat(fundBalance()).isEqualTo(1_000_000);
    }

    @Test
    void 다른_권역_행사는_보이지_않고_중단하면_404() throws Exception {
        fund(100_000);
        long id = Long.parseLong(idOf(create(fixed(5000, 10)).andExpect(status().isCreated())));
        String otherLogin = "a" + System.nanoTime();
        accounts.createAssociation(otherRegion, otherLogin, PASSWORD);
        MockHttpSession other = login(otherLogin);

        mvc.perform(get("/api/v1/association/coupon-events").session(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/api/v1/association/coupon-events/" + id + "/stop").session(other).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        stop(999_999_999L).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT stopped_at IS NULL FROM association_coupon_event WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void 비로그인은_401_사장님은_403_CSRF가_없으면_403() throws Exception {
        mvc.perform(get("/api/v1/association/coupon-events")).andExpect(status().isUnauthorized());
        String ownerLogin = "o" + System.nanoTime();
        accounts.createOwner(storeA, ownerLogin, PASSWORD);
        MockHttpSession owner = login(ownerLogin);

        mvc.perform(get("/api/v1/association/coupon-events").session(owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(post("/api/v1/association/coupon-events").session(owner).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fixed(5000, 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(post("/api/v1/association/coupon-events").session(association)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(fixed(5000, 10))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(eventCount()).isZero();
    }

    // ---- 준비 ----

    private Map<String, Object> fixed(int amount, int quantity) {
        Map<String, Object> b = base(quantity);
        b.put("benefitType", "fixed_discount");
        b.put("discountAmount", amount);
        return b;
    }

    private Map<String, Object> percent(int rate, Integer max, int quantity) {
        Map<String, Object> b = base(quantity);
        b.put("benefitType", "percent_discount");
        b.put("discountPercent", rate);
        b.put("maxDiscountAmount", max);
        return b;
    }

    private Map<String, Object> base(int quantity) {
        Map<String, Object> b = new HashMap<>();
        b.put("title", "주말 할인");
        b.put("eligibleStoreScope", "all");
        b.put("totalQuantity", quantity);
        b.put("issueStartAt", ISSUE_START);
        b.put("issueEndAt", ISSUE_END);
        b.put("validStartAt", VALID_START);
        b.put("validEndAt", VALID_END);
        return b;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private static Map<String, Object> selected(Map<String, Object> body, long... stores) {
        body.put("eligibleStoreScope", "selected");
        List<String> ids = new ArrayList<>();
        for (long s : stores) {
            ids.add(String.valueOf(s));
        }
        body.put("storeIds", ids);
        return body;
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/association/coupon-events").session(association).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions stop(long id) throws Exception {
        return mvc.perform(post("/api/v1/association/coupon-events/" + id + "/stop").session(association)
                .with(TestCsrf.csrf(mvc)));
    }

    /** 회원 {@code issued}명이 받고 그중 {@code used}장은 5,000원 할인으로 사용함(E19 · E20 대신 직접 넣음). */
    private void issue(long eventId, int issued, int used) {
        jdbc.update("UPDATE association_coupon_event SET issued_count = ? WHERE id = ?", issued, eventId);
        for (int i = 0; i < issued; i++) {
            long customer = insert("INSERT INTO customer (type) VALUES ('member')");
            customers.add(customer);
            boolean isUsed = i < used;
            jdbc.update("""
                    INSERT INTO coupon (customer_id, issuer_kind, association_event_id, downloaded_at, expires_at, status,
                                        discount_applied)
                    VALUES (?, 'association', ?, '2026-11-30 03:00:00', '2026-12-14 15:00:00', ?, ?)
                    """, customer, eventId, isUsed ? "used" : "downloaded", isUsed ? 5000 : null);
        }
    }

    private void fund(long amount) {
        wallets.post(WalletOwner.fund(region), WalletTxType.FUND_DEPOSITED, amount, null);
    }

    private long fundBalance() {
        return wallets.balance(WalletOwner.fund(region));
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM association_coupon_event WHERE region_id = ?", Integer.class, region);
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private long store(long regionId, String name, String joinStatus) {
        jdbc.update("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (?, ?, 'cafe', 'center', ?)",
                regionId, name, joinStatus);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
