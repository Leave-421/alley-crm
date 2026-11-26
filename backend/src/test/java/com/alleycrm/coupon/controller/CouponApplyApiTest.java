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
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 쿠폰 적용(CONTRACTS_API 3.5, FR-CPN-05 · 06): 예약 화면의 사용 가능한 쿠폰과 예약 신청 단계 60 · 90, 거절 때 적용 취소.
 * 회원은 실제 가입 · 로그인 흐름으로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponApplyApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-11-27 10:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    /** 2026-11-28 18:00 Asia/Seoul */
    private static final String START = "2026-11-28T09:00:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AppClock clock;

    @Autowired
    AccountService accountService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ReservationStateService reservationStates;

    long region;
    long store;
    long otherStore;
    long event;
    long customerId;
    MockHttpSession member;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('쿠폰 적용 권역')");
        store = store("B");
        otherStore = store("C");
        event = fixedEvent(store);

        String loginId = "m" + System.nanoTime();
        accountService.signupCustomer(loginId, PASSWORD, "회원", null, null);
        customerId = jdbc.queryForObject(
                "SELECT c.id FROM customer c JOIN account a ON a.id = c.account_id WHERE a.login_id = ?",
                Long.class, loginId);
        member = login(loginId);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- 예약 화면의 사용 가능한 쿠폰 ----

    @Test
    void 사용_가능한_쿠폰은_그_가게의_기한_안_다운로드됨만_만료가_가까운_순으로_FR_CPN_06_1() throws Exception {
        long later = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");
        long sooner = coupon(event, store, NOW_UTC.minusDays(5), "downloaded");
        coupon(event, store, NOW_UTC.minusDays(8), "downloaded"); // 기한 지남(만료 작업 전)
        coupon(event, store, NOW_UTC.minusDays(2), "applied");
        coupon(fixedEvent(otherStore), otherStore, NOW_UTC.minusDays(1), "downloaded");

        mvc.perform(get("/api/v1/coupons/usable").param("storeId", String.valueOf(store)).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].couponId").value(Matchers.contains(
                        String.valueOf(sooner), String.valueOf(later))))
                .andExpect(jsonPath("$.items[0].status").value("downloaded"))
                .andExpect(jsonPath("$.items[0].benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.items[0].discountAmount").value(3000))
                .andExpect(jsonPath("$.items[0].storeId").value(String.valueOf(store)))
                .andExpect(jsonPath("$.items[0].selected").doesNotExist());
    }

    @Test
    void 사용_가능한_쿠폰은_회원만_보고_가게는_보여야_한다() throws Exception {
        mvc.perform(get("/api/v1/coupons/usable").param("storeId", String.valueOf(store)))
                .andExpect(status().isUnauthorized());

        String ownerLogin = "o" + System.nanoTime();
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES (?, ?, 'owner', ?)",
                ownerLogin, passwordEncoder.encode(PASSWORD), store);
        mvc.perform(get("/api/v1/coupons/usable").param("storeId", String.valueOf(store)).session(login(ownerLogin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));

        for (String bad : new String[] {"abc", "0"}) {
            mvc.perform(get("/api/v1/coupons/usable").param("storeId", bad).session(member))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/coupons/usable").session(member)).andExpect(status().isBadRequest());

        jdbc.update("UPDATE store SET join_status = 'withdrawn' WHERE id = ?", otherStore);
        mvc.perform(get("/api/v1/coupons/usable").param("storeId", String.valueOf(otherStore)).session(member))
                .andExpect(status().isNotFound());
    }

    // ---- 예약 신청에서 적용 ----

    @Test
    void 기한_안에_고른_쿠폰이_예약에_적용된다_FR_CPN_05_1() throws Exception {
        // 쿠폰 기간 7일, 다운로드 후 6일 23시간
        long coupon = coupon(event, store, NOW_UTC.minusDays(6).minusHours(23), "downloaded");

        String reservationId = idOf(request(memberBody(String.valueOf(coupon)), member)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.couponId").value(String.valueOf(coupon)))
                .andExpect(jsonPath("$.coupon.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.coupon.discountAmount").value(3000))
                .andExpect(jsonPath("$.coupon.discountPercent").value(Matchers.nullValue())));

        assertThat(statusOf(coupon)).isEqualTo("applied");
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM coupon WHERE id = ?", Long.class, coupon))
                .isEqualTo(Long.valueOf(reservationId));
    }

    @Test
    void 정률_할인_쿠폰은_신청_때_할인액이_없고_할인율을_준다() throws Exception {
        long percent = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_rate, max_discount_amount, "
                + "valid_days, total_quantity, issued_count, confirmed_at, stopped_at) VALUES (" + store
                + ", 'percent_discount', 10, 5000, 7, 10, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long coupon = coupon(percent, store, NOW_UTC.minusDays(1), "downloaded");

        request(memberBody(String.valueOf(coupon)), member)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.benefitType").value("percent_discount"))
                .andExpect(jsonPath("$.coupon.discountAmount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.coupon.discountPercent").value(10))
                .andExpect(jsonPath("$.coupon.maxDiscountAmount").value(5000));
    }

    @Test
    void 쿠폰을_고르지_않으면_적용_없고_쿠폰은_그대로_FR_CPN_06_2() throws Exception {
        long coupon = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");

        request(memberBody(null), member)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon").value(Matchers.nullValue()));
        assertThat(statusOf(coupon)).isEqualTo("downloaded");
    }

    @Test
    void 비회원이_보낸_쿠폰은_무시한다_FR_CPN_06_3() throws Exception {
        long coupon = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");
        Map<String, Object> body = memberBody(String.valueOf(coupon));
        body.put("guest", Map.of("name", "손님", "phone", "01012345678"));

        request(body, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon").value(Matchers.nullValue()));
        assertThat(statusOf(coupon)).isEqualTo("downloaded");
    }

    @Test
    void 두_장_중_고른_쿠폰만_적용된다_FR_CPN_06_4() throws Exception {
        long oldEvent = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, "
                + "total_quantity, issued_count, confirmed_at, stopped_at) VALUES (" + store
                + ", 'fixed_discount', 1000, 7, 10, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long oldCoupon = coupon(oldEvent, store, NOW_UTC.minusDays(3), "downloaded");
        long newCoupon = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");

        request(memberBody(String.valueOf(newCoupon)), member).andExpect(status().isCreated());

        assertThat(statusOf(newCoupon)).isEqualTo("applied");
        assertThat(statusOf(oldCoupon)).isEqualTo("downloaded");
    }

    @Test
    void 쿠폰_두_장을_한_예약에_보내면_400이고_저장하지_않는다_FR_CPN_06_5() throws Exception {
        long a = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");
        long b = coupon(event, store, NOW_UTC.minusDays(2), "downloaded");

        request(memberBody(List.of(String.valueOf(a), String.valueOf(b))), member)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        assertThat(statusOf(a)).isEqualTo("downloaded");
        assertThat(statusOf(b)).isEqualTo("downloaded");
        assertThat(reservationCount()).isZero();
    }

    @Test
    void 남의_쿠폰이나_없는_쿠폰이면_404이고_예약을_저장하지_않는다() throws Exception {
        String otherLogin = "x" + System.nanoTime();
        accountService.signupCustomer(otherLogin, PASSWORD, "다른 회원", null, null);
        long othersCustomer = jdbc.queryForObject(
                "SELECT c.id FROM customer c JOIN account a ON a.id = c.account_id WHERE a.login_id = ?",
                Long.class, otherLogin);
        long others = insertCoupon(othersCustomer, event, store, NOW_UTC.minusDays(1), "downloaded");

        for (String id : new String[] {String.valueOf(others), "999999999"}) {
            request(memberBody(id), member)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
        assertThat(statusOf(others)).isEqualTo("downloaded");
        assertThat(reservationCount()).isZero();
    }

    @Test
    void 다른_가게_쿠폰_이미_쓴_쿠폰_기한이_지난_쿠폰은_409() throws Exception {
        long otherStores = coupon(fixedEvent(otherStore), otherStore, NOW_UTC.minusDays(1), "downloaded");
        long applied = coupon(event, store, NOW_UTC.minusDays(1), "applied");
        long expiredStatus = coupon(event, store, NOW_UTC.minusDays(1), "expired");
        long canceled = coupon(event, store, NOW_UTC.minusDays(1), "apply_canceled");
        long pastDue = coupon(event, store, NOW_UTC.minusDays(7).minusMinutes(1), "downloaded");

        for (long coupon : new long[] {otherStores, applied, expiredStatus, canceled, pastDue}) {
            request(memberBody(String.valueOf(coupon)), member)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("conflict"));
        }
        assertThat(statusOf(pastDue)).isEqualTo("downloaded");
        assertThat(reservationCount()).isZero();
    }

    // ---- 거절 → 적용 취소 ----

    @Test
    void 가게가_거절하면_적용_취소가_되고_확정은_그대로_FR_CPN_05_4() throws Exception {
        long rejected = coupon(event, store, NOW_UTC.minusDays(1), "downloaded");
        long rejectedReservation = Long.parseLong(idOf(request(memberBody(String.valueOf(rejected)), member)
                .andExpect(status().isCreated())));
        long confirmed = coupon(event, store, NOW_UTC.minusDays(2), "downloaded");
        Map<String, Object> second = memberBody(String.valueOf(confirmed));
        second.put("startAt", "2026-11-28T10:00:00Z");
        long confirmedReservation = Long.parseLong(idOf(request(second, member).andExpect(status().isCreated())));

        reservationStates.reject(store, rejectedReservation);
        reservationStates.confirm(store, confirmedReservation);

        assertThat(statusOf(rejected)).isEqualTo("apply_canceled");
        assertThat(statusOf(confirmed)).isEqualTo("applied");
    }

    // ---- 준비 ----

    private long store(String name) {
        return insert("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (%d, '%s', 'cafe', 'center', 'approved', '10:00', '22:00', 5, 7)
                """.formatted(region, name));
    }

    /** 확정된 정액 할인 3,000원 행사(쿠폰 기간 7일). */
    private long fixedEvent(long storeId) {
        return insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + storeId + ", 'fixed_discount', 3000, 7, 10, 0, UTC_TIMESTAMP(6))");
    }

    /** 이 회원의 쿠폰. 만료 시각은 다운로드 + 7 × 24시간. */
    private long coupon(long eventId, long storeId, LocalDateTime downloadedAt, String status) {
        return insertCoupon(customerId, eventId, storeId, downloadedAt, status);
    }

    private long insertCoupon(long customer, long eventId, long storeId, LocalDateTime downloadedAt, String status) {
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, customer, eventId, storeId, downloadedAt, downloadedAt.plusDays(7), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Map<String, Object> memberBody(Object couponId) {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(store));
        b.put("startAt", START);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("couponId", couponId);
        return b;
    }

    private ResultActions request(Map<String, Object> body, MockHttpSession session) throws Exception {
        var builder = post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (session != null) {
            builder.session(session);
        }
        return mvc.perform(builder);
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String statusOf(long coupon) {
        return jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon);
    }

    private int reservationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE store_id = ?", Integer.class, store);
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
