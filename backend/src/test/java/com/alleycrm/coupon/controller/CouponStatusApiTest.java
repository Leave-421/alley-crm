package com.alleycrm.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.coupon.domain.CouponIssuer;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.service.CouponApplyService;
import com.alleycrm.coupon.service.CouponExpiryService;
import com.alleycrm.coupon.service.CouponStatusService;
import com.alleycrm.coupon.service.CouponVisitService;
import com.alleycrm.reservation.ReservationStateService;
import com.alleycrm.reservation.ReservationStatusChangedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 상태 · 만료 · 사용 확인(CONTRACTS_API 3.6, FR-CPN-05 · 07 · 08). 방문 처리는 D11의 실제 메서드를 부른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponStatusApiTest {

    private static final String PASSWORD = "password1";
    /** 2026-12-01 10:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-12-01T01:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AppClock clock;

    @Autowired
    ReservationStateService reservationStates;

    @Autowired
    CouponVisitService visitService;

    @Autowired
    CouponExpiryService expiryService;

    @Autowired
    CouponStatusService statusService;

    @Autowired
    CouponApplyService applyService;

    @Autowired
    ApplicationEventPublisher events;

    long region;
    long store;
    long otherStore;
    long fixedEvent;
    long customer;
    MockHttpSession owner;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('쿠폰 상태 권역')");
        store = store("B");
        otherStore = store("C");
        fixedEvent = event(store, "'fixed_discount', 5000, NULL, NULL, NULL");
        customer = insert("INSERT INTO customer (type) VALUES ('member')");
        owner = login(owner(store));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- 방문 할인 ----

    @Test
    void 방문_처리하면_실제_할인액을_남기고_적용됨_그대로_FR_CPN_07_3() {
        long reservation = reservation(store, "confirmed");
        long coupon = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "applied", reservation);

        reservationStates.visit(store, reservation, 4000);

        assertThat(statusOf(coupon)).isEqualTo("applied");
        assertThat(jdbc.queryForObject("SELECT discount_applied FROM coupon WHERE id = ?", Integer.class, coupon))
                .isEqualTo(4000);
        assertThat(visitService.discountFor(reservation, 4000))
                .contains(new VisitCouponDiscount(coupon, CouponIssuer.STORE, 4000, 4000));
    }

    @Test
    void 정률과_서비스_쿠폰의_할인과_비용_FR_CPN_07_5() {
        long percent = event(store, "'percent_discount', NULL, 10, 5000, NULL");
        long percentReservation = reservation(store, "confirmed");
        long percentCoupon = coupon(percent, store, NOW_UTC.minusDays(1), "applied", percentReservation);
        long service = event(store, "'service', NULL, NULL, NULL, 4500");
        long serviceReservation = reservation(store, "confirmed");
        long serviceCoupon = coupon(service, store, NOW_UTC.minusDays(1), "applied", serviceReservation);

        assertThat(visitService.discountFor(percentReservation, 80000))
                .contains(new VisitCouponDiscount(percentCoupon, CouponIssuer.STORE, 5000, 5000));
        assertThat(visitService.discountFor(serviceReservation, 20000))
                .contains(new VisitCouponDiscount(serviceCoupon, CouponIssuer.STORE, 0, 4500));
    }

    @Test
    void 쿠폰이_없는_예약은_할인이_없다() {
        long reservation = reservation(store, "confirmed");

        reservationStates.visit(store, reservation, 10000);

        assertThat(visitService.discountFor(reservation, 10000)).isEmpty();
        assertThat(applyService.appliedCouponFor(reservation)).isNull();
    }

    // ---- 사용 확인 ----

    @Test
    void 방문_처리된_예약의_쿠폰을_사용_확인하면_사용_완료_FR_CPN_08_1() throws Exception {
        long reservation = reservation(store, "confirmed");
        long coupon = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "applied", reservation);
        reservationStates.visit(store, reservation, 30000);

        mvc.perform(post("/api/v1/owner/reservations/" + reservation + "/coupon-use").session(owner)
                        .with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(String.valueOf(coupon)))
                .andExpect(jsonPath("$.reservationId").value(String.valueOf(reservation)))
                .andExpect(jsonPath("$.status").value("used"))
                .andExpect(jsonPath("$.discountAmount").value(5000))
                .andExpect(jsonPath("$.usedAt").value(Matchers.endsWith("Z")));
        assertThat(statusOf(coupon)).isEqualTo("used");
        assertThat(applyService.appliedCouponFor(reservation).couponId()).isEqualTo(String.valueOf(coupon));
    }

    @Test
    void 기한이_지나도_적용된_쿠폰은_방문_뒤_사용_완료가_된다_FR_CPN_05_3() throws Exception {
        // 다운로드 후 12일에 방문: 쿠폰 기한(7일)은 지났지만 이미 적용됨
        long reservation = reservation(store, "confirmed");
        long coupon = coupon(fixedEvent, store, NOW_UTC.minusDays(12), "applied", reservation);
        expiryService.expireDue();
        reservationStates.visit(store, reservation, 30000);

        mvc.perform(post("/api/v1/owner/reservations/" + reservation + "/coupon-use").session(owner)
                        .with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk());
        assertThat(statusOf(coupon)).isEqualTo("used");
    }

    @Test
    void 방문_처리_전이거나_쿠폰이_없거나_이미_확인했으면_409_FR_CPN_08_2() throws Exception {
        long notVisited = reservation(store, "confirmed");
        long coupon = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "applied", notVisited);
        long withoutCoupon = reservation(store, "confirmed");
        reservationStates.visit(store, withoutCoupon, 10000);

        for (long reservation : new long[] {notVisited, withoutCoupon}) {
            mvc.perform(post("/api/v1/owner/reservations/" + reservation + "/coupon-use").session(owner)
                            .with(TestCsrf.csrf(mvc)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("conflict"));
        }
        assertThat(statusOf(coupon)).isEqualTo("applied");

        reservationStates.visit(store, notVisited, 10000);
        mvc.perform(post("/api/v1/owner/reservations/" + notVisited + "/coupon-use").session(owner)
                        .with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/owner/reservations/" + notVisited + "/coupon-use").session(owner)
                        .with(TestCsrf.csrf(mvc)))
                .andExpect(status().isConflict());
    }

    @Test
    void 다른_가게_예약이나_없는_예약이면_404() throws Exception {
        long others = reservation(otherStore, "confirmed");
        coupon(event(otherStore, "'fixed_discount', 3000, NULL, NULL, NULL"), otherStore, NOW_UTC.minusDays(1),
                "applied", others);
        reservationStates.visit(otherStore, others, 10000);

        for (String id : new String[] {String.valueOf(others), "999999999", "abc"}) {
            mvc.perform(post("/api/v1/owner/reservations/" + id + "/coupon-use").session(owner)
                            .with(TestCsrf.csrf(mvc)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Test
    void 사장님만_CSRF와_함께_확인한다() throws Exception {
        long reservation = reservation(store, "confirmed");
        String path = "/api/v1/owner/reservations/" + reservation + "/coupon-use";

        mvc.perform(post(path).with(TestCsrf.csrf(mvc))).andExpect(status().isUnauthorized());
        String memberLogin = "u" + System.nanoTime();
        jdbc.update("INSERT INTO account (login_id, password_hash, role) VALUES (?, ?, 'customer')",
                memberLogin, passwordEncoder.encode(PASSWORD));
        mvc.perform(post(path).session(login(memberLogin)).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(post(path).session(owner).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    // ---- 만료 ----

    @Test
    void 기한이_지난_다운로드됨_쿠폰만_기간_만료가_된다_FR_CPN_05_2() {
        long due = coupon(fixedEvent, store, NOW_UTC.minusDays(7), "downloaded", null); // 만료 시각 = 지금
        long valid = coupon(fixedEvent, store, NOW_UTC.minusDays(6).minusHours(23), "downloaded", null);
        long appliedPast = coupon(fixedEvent, store, NOW_UTC.minusDays(10), "applied", reservation(store, "confirmed"));

        assertThat(expiryService.expireDue()).isGreaterThanOrEqualTo(1);

        assertThat(statusOf(due)).isEqualTo("expired");
        assertThat(statusOf(valid)).isEqualTo("downloaded");
        assertThat(statusOf(appliedPast)).isEqualTo("applied");
    }

    // ---- 가게 운영 중단 · 탈퇴 ----

    @Test
    void 가게가_운영_중단하면_그_가게의_미사용_쿠폰이_적용_취소_FR_CPN_07_4() {
        long downloaded = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "downloaded", null);
        long applied = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "applied", reservation(store, "confirmed"));
        long used = coupon(fixedEvent, store, NOW_UTC.minusDays(3), "used", reservation(store, "visited"));
        long expired = coupon(fixedEvent, store, NOW_UTC.minusDays(9), "expired", null);
        long otherStores = coupon(event(otherStore, "'fixed_discount', 3000, NULL, NULL, NULL"), otherStore,
                NOW_UTC.minusDays(1), "downloaded", null);

        assertThat(statusService.cancelUnusedForStore(store)).isEqualTo(2);

        assertThat(statusOf(downloaded)).isEqualTo("apply_canceled");
        assertThat(statusOf(applied)).isEqualTo("apply_canceled");
        assertThat(statusOf(used)).isEqualTo("used");
        assertThat(statusOf(expired)).isEqualTo("expired");
        assertThat(statusOf(otherStores)).isEqualTo("downloaded");
    }

    // ---- 늦은 방문 처리 ----

    @Test
    void 자동_취소된_예약을_늦게_방문_처리하면_쿠폰이_적용됨으로_돌아온다() {
        long reservation = reservation(store, "confirmed");
        long coupon = coupon(fixedEvent, store, NOW_UTC.minusDays(1), "apply_canceled", reservation);

        // 늦은 방문 처리(D23)가 내보낼 상태 변경 이벤트
        events.publishEvent(new ReservationStatusChangedEvent(reservation, store, customer, "canceled", "visited",
                null, false));

        assertThat(statusOf(coupon)).isEqualTo("applied");
    }

    // ---- 준비 ----

    private long store(String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time) VALUES ("
                + region + ", '" + name + "', 'cafe', 'center', 'approved', '10:00', '22:00')");
    }

    /** 혜택 칸: benefit_type, discount_amount, discount_rate, max_discount_amount, service_value. */
    private long event(long storeId, String benefit) {
        String serviceName = benefit.startsWith("'service'") ? "'아메리카노'" : "NULL";
        return insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, discount_rate, "
                + "max_discount_amount, service_value, service_name, valid_days, total_quantity, issued_count, "
                + "confirmed_at, stopped_at) VALUES (" + storeId + ", " + benefit + ", " + serviceName
                + ", 7, 10, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    private long reservation(long storeId, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), storeId, customer,
                NOW_UTC.plusHours(5), status, NOW_UTC.minusDays(1));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 만료 시각은 다운로드 + 7 × 24시간. */
    private long coupon(long eventId, long storeId, LocalDateTime downloadedAt, String status, Long reservation) {
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status,
                                    reservation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, customer, eventId, storeId, downloadedAt, downloadedAt.plusDays(7), status, reservation);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String statusOf(long coupon) {
        return jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon);
    }

    private String owner(long storeId) {
        String loginId = "w" + System.nanoTime();
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES (?, ?, 'owner', ?)",
                loginId, passwordEncoder.encode(PASSWORD), storeId);
        return loginId;
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
