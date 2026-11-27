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
import com.alleycrm.coupon.service.CouponApplyService;
import com.alleycrm.coupon.service.CouponStatusService;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 상인회 쿠폰 적용 · 기금 부담(CONTRACTS_API 3.10, FR-CPN-14 · FR-CPN-06). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AssociationCouponApplyApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-11-30 12:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-11-30T03:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    /** 2026-12-01 18:00 Asia/Seoul */
    private static final String START = "2026-12-01T09:00:00Z";

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
    ReservationStateService reservationStates;

    @Autowired
    CouponStatusService statusService;

    @Autowired
    CouponApplyService applyService;

    long region;
    long storeB;
    long storeC;
    long storeD;
    long customer;
    MockHttpSession member;
    long storeCoupon;
    long associationEvent;
    long associationCoupon;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('상인회 쿠폰 적용 권역')");
        storeB = store("B");
        storeC = store("C");
        storeD = store("D");
        String loginId = "m" + System.nanoTime();
        long accountId = accounts.signupCustomer(loginId, PASSWORD, "회원", null, null).id();
        customer = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        member = login(loginId);

        long storeEvent = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + storeB + ", 'fixed_discount', 3000, 7, 10, 1, '2026-11-29 00:00:00')");
        storeCoupon = insert("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (%d, %d, %d, '2026-11-30 02:00:00', '2026-12-07 02:00:00', 'downloaded')
                """.formatted(customer, storeEvent, storeB));
        associationEvent = associationEvent("selected", "2026-11-30 00:00:00", storeB, storeC);
        associationCoupon = associationCoupon(associationEvent);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void 예약_화면_목록에_그_가게에서_쓸_수_있는_상인회_쿠폰이_같이_나온다_FR_CPN_06() throws Exception {
        usable(storeB).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].couponId").value(Matchers.contains(id(storeCoupon), id(associationCoupon))))
                .andExpect(jsonPath("$.items[0].issuerKind").value("store"))
                .andExpect(jsonPath("$.items[0].associationCouponEventId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].issuerKind").value("association"))
                .andExpect(jsonPath("$.items[1].associationCouponEventId").value(id(associationEvent)))
                .andExpect(jsonPath("$.items[1].storeId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].eventId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].discountAmount").value(5000));
        usable(storeC).andExpect(jsonPath("$.items[*].couponId").value(Matchers.contains(id(associationCoupon))));
        usable(storeD).andExpect(jsonPath("$.items").isEmpty()); // 가게 쿠폰은 B, 상인회 쿠폰은 B · C만
    }

    @Test
    void 두_장은_거절되고_한_장만_고르면_그_쿠폰만_적용된다_FR_CPN_14_1() throws Exception {
        Map<String, Object> both = body(storeB, null);
        both.put("couponId", List.of(id(storeCoupon), id(associationCoupon)));
        request(both).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid"));

        String reservationId = json.readTree(request(body(storeB, associationCoupon)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.couponId").value(id(associationCoupon)))
                .andExpect(jsonPath("$.coupon.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.coupon.discountAmount").value(5000))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        assertThat(statusOf(associationCoupon)).isEqualTo("applied");
        assertThat(statusOf(storeCoupon)).isEqualTo("downloaded");
        AppliedCoupon applied = applyService.appliedCouponFor(Long.parseLong(reservationId));
        assertThat(applied.couponId()).isEqualTo(id(associationCoupon));
        assertThat(applied.serviceName()).isNull();
    }

    @Test
    void 쓸_수_없는_가게나_쓸_수_있는_기간_전이면_거절_FR_CPN_14_2() throws Exception {
        request(body(storeD, associationCoupon)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        long later = associationEvent("selected", "2026-12-01 00:00:00", storeB);
        long laterCoupon = associationCoupon(later);

        request(body(storeB, laterCoupon)).andExpect(status().isConflict()); // 신청 시각 < 쓸 수 있는 기간 시작
        usable(storeB).andExpect(jsonPath("$.items[*].couponId").value(Matchers.not(Matchers.hasItem(id(laterCoupon)))));
        assertThat(statusOf(associationCoupon)).isEqualTo("downloaded");
        assertThat(statusOf(laterCoupon)).isEqualTo("downloaded");
    }

    @Test
    void 모든_참여_가게_행사는_권역의_승인된_가게에서_쓴다() throws Exception {
        long all = associationEvent("all", "2026-11-30 00:00:00");
        long allCoupon = associationCoupon(all);

        usable(storeD).andExpect(jsonPath("$.items[*].couponId").value(Matchers.contains(id(allCoupon))));
        request(body(storeD, allCoupon)).andExpect(status().isCreated());
        assertThat(statusOf(allCoupon)).isEqualTo("applied");
    }

    @Test
    void 소개_예약을_방문_처리하면_수수료는_할인_후_금액_쿠폰_비용은_기금_부담_FR_CPN_14_3() {
        long reservation = referralReservation(storeB, storeC);
        applyTo(associationCoupon, reservation);

        reservationStates.visit(storeB, reservation, 30000);

        Map<String, Object> fee = jdbc.queryForMap("SELECT * FROM referral_fee WHERE reservation_id = ?", reservation);
        assertThat(fee.get("coupon_discount")).isEqualTo(5000);
        assertThat(fee.get("fee_base")).isEqualTo(25000);
        assertThat(fee.get("fee_amount")).isEqualTo(1000);
        assertThat(fee.get("coupon_cost")).isEqualTo(0); // 가게 부담 아님
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet WHERE owner_type = 'store' AND owner_id = ?",
                Long.class, storeB)).isEqualTo(-1000); // 수수료만 빠지고 쿠폰 비용은 빠지지 않음
        assertThat(jdbc.queryForObject("SELECT discount_applied FROM coupon WHERE id = ?", Integer.class, associationCoupon))
                .isEqualTo(5000); // 기금 예산에서 쓴 금액
    }

    @Test
    void 방문_처리만으로는_적용됨_그대로이고_사용_확인_뒤_사용_완료_FR_CPN_14_4() {
        long reservation = reservation(storeB, "confirmed", null, null);
        applyTo(associationCoupon, reservation);

        reservationStates.visit(storeB, reservation, 12000);
        assertThat(statusOf(associationCoupon)).isEqualTo("applied");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE reservation_id = ?", Integer.class, reservation))
                .isZero(); // 소개를 만들지 않음

        statusService.confirmUse(storeB, reservation);
        assertThat(statusOf(associationCoupon)).isEqualTo("used");
    }

    // ---- 준비 ----

    /** 정액 5,000원 상인회 행사. 쓸 수 있는 기간 끝 2026-12-14 15:00 UTC. */
    private long associationEvent(String scope, String validStart, long... stores) {
        long id = insert("""
                INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_amount, total_quantity,
                    issued_count, eligible_store_scope, issue_start_at, issue_end_at, valid_start_at, valid_end_at,
                    reserved_budget, confirmed_at)
                VALUES (%d, '골목 할인', 'fixed_discount', 5000, 10, 1, '%s', '2026-11-29 00:00:00', '2026-12-07 00:00:00',
                    '%s', '2026-12-14 15:00:00', 50000, '2026-11-29 00:00:00')
                """.formatted(region, scope, validStart));
        for (long store : stores) {
            jdbc.update("INSERT INTO association_coupon_event_store (event_id, store_id) VALUES (?, ?)", id, store);
        }
        return id;
    }

    private long associationCoupon(long event) {
        return insert("""
                INSERT INTO coupon (customer_id, issuer_kind, association_event_id, downloaded_at, expires_at, status)
                VALUES (%d, 'association', %d, '2026-11-30 01:00:00', '2026-12-14 15:00:00', 'downloaded')
                """.formatted(customer, event));
    }

    private void applyTo(long coupon, long reservation) {
        jdbc.update("UPDATE coupon SET status = 'applied', reservation_id = ? WHERE id = ?", reservation, coupon);
    }

    /** 기준 가게 예약 → 정식 제안 → 받은 가게의 확정된 소개 후보 예약(D12가 남기는 칸을 직접 넣음). */
    private long referralReservation(long receiving, long sender) {
        long base = reservation(sender, "confirmed", null, null);
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + receiving + ", '2026-11-30 02:00:00')");
        return reservation(receiving, "confirmed", proposal, sender);
    }

    private long reservation(long store, String status, Long proposal, Long sender) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at,
                                         course_proposal_id, sender_store_id)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), store, customer, NOW_UTC.plusHours(5), status,
                NOW_UTC.minusHours(1), proposal, sender);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Map<String, Object> body(long store, Long couponId) {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(store));
        b.put("startAt", START);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("couponId", couponId == null ? null : String.valueOf(couponId));
        return b;
    }

    private ResultActions request(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/reservations").session(member).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions usable(long store) throws Exception {
        return mvc.perform(get("/api/v1/coupons/usable").param("storeId", String.valueOf(store)).session(member));
    }

    private String statusOf(long coupon) {
        return jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon);
    }

    private static String id(long value) {
        return String.valueOf(value);
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
