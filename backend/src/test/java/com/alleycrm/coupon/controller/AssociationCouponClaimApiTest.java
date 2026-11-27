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
import com.alleycrm.coupon.AssociationCouponOpenedEvent;
import com.alleycrm.wallet.WalletOwner;
import com.alleycrm.wallet.WalletService;
import com.alleycrm.wallet.WalletTxType;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 상인회 쿠폰 받기(CONTRACTS_API 3.9, FR-CPN-13). 동시 받기는 {@code AssociationCouponClaimConcurrencyTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
@Transactional
class AssociationCouponClaimApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-11-30 12:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-11-30T03:00:00Z");
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
    ApplicationEvents events;

    long region;
    long storeA;
    long storeB;
    MockHttpSession member1;
    MockHttpSession member2;
    MockHttpSession member3;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('상인회 쿠폰 받기 권역')");
        storeA = store("구름 디저트");
        storeB = store("달빛 포차");
        member1 = member();
        member2 = member();
        member3 = member();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void 받으면_201이고_상인회_쿠폰으로_남는다() throws Exception {
        long event = event(10, "2026-11-30 02:00:00", "2026-12-07 15:00:00", false);

        claim(member1, event).andExpect(status().isCreated())
                .andExpect(jsonPath("$.issuerKind").value("association"))
                .andExpect(jsonPath("$.associationCouponEventId").value(String.valueOf(event)))
                .andExpect(jsonPath("$.eventId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.storeId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value("downloaded"))
                .andExpect(jsonPath("$.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.discountAmount").value(5000))
                .andExpect(jsonPath("$.expiresAt").value(VALID_END));

        assertThat(issuedCount(event)).isEqualTo(1);
        offer(member1, event).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.myCouponId").isString())
                .andExpect(jsonPath("$.stores[*].name").value(Matchers.contains("구름 디저트", "달빛 포차")))
                .andExpect(jsonPath("$.issuedCount").doesNotExist())
                .andExpect(jsonPath("$.remainingQuantity").doesNotExist());
        offer(member2, event).andExpect(jsonPath("$.status").value("available"))
                .andExpect(jsonPath("$.myCouponId").value(Matchers.nullValue()));
    }

    @Test
    void 수량이_다_나가면_거절하고_카드는_마감_FR_CPN_13_1() throws Exception {
        long event = event(2, "2026-11-30 02:00:00", "2026-12-07 15:00:00", false);
        claim(member1, event).andExpect(status().isCreated());
        claim(member2, event).andExpect(status().isCreated());

        claim(member3, event).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("conflict"));

        assertThat(issuedCount(event)).isEqualTo(2);
        assertThat(couponCount(event)).isEqualTo(2);
        offer(member3, event).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("closed"));
    }

    @Test
    void 이미_받은_회원은_다시_받을_수_없다_FR_CPN_13_2() throws Exception {
        long event = event(10, "2026-11-30 02:00:00", "2026-12-07 15:00:00", false);
        claim(member1, event).andExpect(status().isCreated());

        claim(member1, event).andExpect(status().isConflict());

        assertThat(issuedCount(event)).isEqualTo(1);
        assertThat(couponCount(event)).isEqualTo(1);
    }

    @Test
    void 받는_기간이_끝났거나_전이거나_중단된_행사는_거절_FR_CPN_13_4() throws Exception {
        long ended = event(10, "2026-11-20 00:00:00", "2026-11-30 02:59:00", false);
        long upcoming = event(10, "2026-12-01 00:00:00", "2026-12-07 15:00:00", false);
        long stopped = event(10, "2026-11-30 02:00:00", "2026-12-07 15:00:00", true);

        for (long event : new long[] {ended, upcoming, stopped}) {
            claim(member1, event).andExpect(status().isConflict());
            assertThat(issuedCount(event)).isZero();
        }
        offer(member1, ended).andExpect(jsonPath("$.status").value("closed"));
        offer(member1, upcoming).andExpect(jsonPath("$.status").value("upcoming"));
        offer(member1, stopped).andExpect(jsonPath("$.status").value("closed"));
    }

    @Test
    void 회원만_쓴다_비로그인_401_사장님_상인회_403_없는_행사_404() throws Exception {
        long event = event(10, "2026-11-30 02:00:00", "2026-12-07 15:00:00", false);
        mvc.perform(get("/api/v1/association-coupon-events/" + event)).andExpect(status().isUnauthorized());
        String ownerLogin = "o" + System.nanoTime();
        accounts.createOwner(storeA, ownerLogin, PASSWORD);
        String associationLogin = "a" + System.nanoTime();
        accounts.createAssociation(region, associationLogin, PASSWORD);

        for (MockHttpSession session : new MockHttpSession[] {login(ownerLogin), login(associationLogin)}) {
            offer(session, event).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("forbidden_role"));
            claim(session, event).andExpect(status().isForbidden());
        }
        offer(member1, 999_999_999L).andExpect(status().isNotFound());
        claim(member1, 999_999_999L).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/association-coupon-events/abc").session(member1)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/association-coupon-events/" + event + "/claim").session(member1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(issuedCount(event)).isZero();
    }

    @Test
    void 행사를_만들면_알림용_이벤트를_내보내고_기금이_모자라_되돌아가면_내보내지_않는다() throws Exception {
        String associationLogin = "a" + System.nanoTime();
        accounts.createAssociation(region, associationLogin, PASSWORD);
        MockHttpSession association = login(associationLogin);
        wallets.post(WalletOwner.fund(region), WalletTxType.FUND_DEPOSITED, 60_000, null);

        String id = json.readTree(createEvent(association, 10).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        createEvent(association, 10).andExpect(status().isConflict()); // 남은 기금 10,000 < 50,000

        assertThat(events.stream(AssociationCouponOpenedEvent.class))
                .containsExactly(new AssociationCouponOpenedEvent(Long.parseLong(id), region));
    }

    @Test
    void 받은_상인회_쿠폰을_쓸_수_없는_가게_예약에_고르면_409_FR_CPN_14_2() throws Exception {
        long notSelected = store("고르지 않은 가게");
        long event = event(10, "2026-11-30 02:00:00", "2026-12-07 15:00:00", false);
        String couponId = json.readTree(claim(member1, event).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("couponId").asString();
        Map<String, Object> body = new HashMap<>();
        body.put("storeId", String.valueOf(notSelected)); // 행사는 A · B만
        body.put("startAt", "2026-12-01T09:00:00Z");
        body.put("partySize", 2);
        body.put("shareConsent", true);
        body.put("couponId", couponId);

        mvc.perform(post("/api/v1/reservations").session(member1).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, Long.parseLong(couponId)))
                .isEqualTo("downloaded");
    }

    // ---- 준비 ----

    /** 정액 5,000원 행사(쓸 수 있는 가게 A · B, 쓸 수 있는 기간 끝 2026-12-14 15:00 UTC). 시각은 UTC 벽시계. */
    private long event(int quantity, String issueStart, String issueEnd, boolean stopped) {
        long id = insert("""
                INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_amount, total_quantity,
                    eligible_store_scope, issue_start_at, issue_end_at, valid_start_at, valid_end_at, reserved_budget,
                    confirmed_at, stopped_at)
                VALUES (%d, '골목 할인', 'fixed_discount', 5000, %d, 'selected', '%s', '%s', '2026-11-20 00:00:00',
                    '2026-12-14 15:00:00', %d, '2026-11-20 00:00:00', %s)
                """.formatted(region, quantity, issueStart, issueEnd, quantity * 5000L,
                stopped ? "'2026-11-30 01:00:00'" : "NULL"));
        jdbc.update("INSERT INTO association_coupon_event_store (event_id, store_id) VALUES (?, ?), (?, ?)",
                id, storeA, id, storeB);
        return id;
    }

    private ResultActions createEvent(MockHttpSession association, int quantity) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("title", "주말 할인");
        b.put("benefitType", "fixed_discount");
        b.put("discountAmount", 5000);
        b.put("eligibleStoreScope", "all");
        b.put("totalQuantity", quantity);
        b.put("issueStartAt", "2026-11-30T02:00:00Z");
        b.put("issueEndAt", "2026-12-07T15:00:00Z");
        b.put("validStartAt", "2026-11-30T02:00:00Z");
        b.put("validEndAt", VALID_END);
        return mvc.perform(post("/api/v1/association/coupon-events").session(association).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private ResultActions offer(MockHttpSession session, long event) throws Exception {
        return mvc.perform(get("/api/v1/association-coupon-events/" + event).session(session));
    }

    private ResultActions claim(MockHttpSession session, long event) throws Exception {
        return mvc.perform(post("/api/v1/association-coupon-events/" + event + "/claim").session(session)
                .with(TestCsrf.csrf(mvc)));
    }

    private int issuedCount(long event) {
        return jdbc.queryForObject("SELECT issued_count FROM association_coupon_event WHERE id = ?", Integer.class, event);
    }

    private int couponCount(long event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE association_event_id = ?", Integer.class, event);
    }

    private MockHttpSession member() throws Exception {
        String loginId = "m" + System.nanoTime();
        accounts.signupCustomer(loginId, PASSWORD, "회원", null, null);
        return login(loginId);
    }

    private long store(String name) {
        return insert("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (%d, '%s', 'cafe', 'center', 'approved', '10:00', '22:00', 5, 7)
                """.formatted(region, name));
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
