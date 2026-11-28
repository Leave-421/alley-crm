package com.alleycrm.analytics;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** C21 추가 지표 · 쿠폰 지표 (FR-ANL-08 · 09). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExtraMetricsApiTest {

    static final String PASSWORD = "password1";
    static final String OWNER_URL = "/api/v1/owner/extra-metrics";
    static final String ASSOCIATION_URL = "/api/v1/association/extra-metrics";
    /** 2026-09-01(화) 12:00 Asia/Seoul */
    static final LocalDateTime DAY1 = LocalDateTime.of(2026, 9, 1, 3, 0);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountService accountService;
    @Autowired
    AppClock clock;

    long regionId;
    /** 카페 */
    long storeA;
    /** 디저트 */
    long storeB;
    String association;
    String owner;
    final List<Long> customers = new ArrayList<>();
    int seq;

    @BeforeEach
    void seed() {
        clock.setTo(Instant.parse("2026-11-28T01:00:00Z"));
        jdbc.update("INSERT INTO region (name) VALUES ('추가 지표 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("추가 지표 A", "cafe");
        storeB = store("추가 지표 B", "dessert");
        association = "xmassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        owner = "xmowner" + System.nanoTime();
        accountService.createOwner(storeA, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE c FROM coupon c JOIN store s ON s.id = c.target_store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE p FROM coupon_policy p JOIN store s ON s.id = p.store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE c FROM store_click c JOIN store s ON s.id = c.store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE e FROM recommendation_exposure e JOIN store s ON s.id = e.recommended_store_id WHERE s.region_id = ?",
                regionId);
        jdbc.update("DELETE r FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE FROM visitor WHERE anon_id LIKE 'xm-%'");
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'xm%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_ANL_08_1_사장님과_상인회가_추가_지표를_본다() throws Exception {
        // 손님 5명이 같은 날 A(카페, 12시) → B(디저트, 14시), 손님 1명은 B(12시) → A(14시)
        List<Long> aVisits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long c = customer();
            aVisits.add(reservation(storeA, c, DAY1, "visited"));
            reservation(storeB, c, DAY1.plusHours(2), "visited");
        }
        long sixth = customer();
        reservation(storeB, sixth, DAY1, "visited");
        reservation(storeA, sixth, DAY1.plusHours(2), "visited");
        reservation(storeA, customers.get(0), DAY1.plusDays(3), "canceled"); // 예약 수에만, 시간대별 예약에는 없음

        // 클릭: A 3번, B 1번
        long anon = visitor(null);
        for (int i = 0; i < 3; i++) {
            click(anon, storeA);
        }
        click(anon, storeB);
        // A 추천 노출 4건 = (방문자, 가게) 3쌍. 손님 1은 본 뒤 예약(전환), 손님 2는 예약한 뒤에 봄(전환 아님)
        long v1 = visitor(customers.get(0));
        expose(v1, storeA, DAY1.minusDays(2));
        expose(v1, storeA, DAY1.minusDays(2).plusMinutes(5));
        expose(visitor(customers.get(1)), storeA, DAY1.plusDays(1));
        expose(anon, storeA, DAY1);

        // A 쿠폰 3장(손님 3명): 사용 완료(방문한 예약에 적용) · 기간 만료 · 다운로드됨
        long policy = couponPolicy(storeA);
        coupon(policy, customers.get(0), "used", aVisits.get(0));
        coupon(policy, customers.get(1), "expired", null);
        coupon(policy, customers.get(2), "downloaded", null);

        mvc.perform(get(OWNER_URL).session(login(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel.clicks").value(3))
                .andExpect(jsonPath("$.funnel.exposures").value(4))
                .andExpect(jsonPath("$.funnel.reservations").value(7))
                .andExpect(jsonPath("$.funnel.visits").value(6))
                .andExpect(jsonPath("$.recommendationConversion.exposedPairs").value(3))
                .andExpect(jsonPath("$.recommendationConversion.convertedPairs").value(1))
                .andExpect(jsonPath("$.recommendationConversion.rate").value(0.3333))
                .andExpect(jsonPath("$.confirmedMoves.count").value(6))
                .andExpect(jsonPath("$.categoryMoves[0].fromCategory").value("cafe"))
                .andExpect(jsonPath("$.categoryMoves[0].toCategory").value("dessert"))
                .andExpect(jsonPath("$.categoryMoves[0].moves").value(5))
                .andExpect(jsonPath("$.categoryMoves[0].ratio").value(0.8333))
                .andExpect(jsonPath("$.categoryMoves[1].fromCategory").value("dessert"))
                .andExpect(jsonPath("$.categoryMoves[1].belowMinimum").value(true))
                .andExpect(jsonPath("$.categoryMoves[1].moves").doesNotExist())
                // 사장님 자기 가게 시간대별 예약은 숨기지 않는다. 9/1은 화요일(2)
                .andExpect(jsonPath("$.hourlyReservations[0].dayOfWeek").value(2))
                .andExpect(jsonPath("$.hourlyReservations[0].hour").value(12))
                .andExpect(jsonPath("$.hourlyReservations[0].reservations").value(5))
                .andExpect(jsonPath("$.hourlyReservations[1].hour").value(14))
                .andExpect(jsonPath("$.hourlyReservations[1].reservations").value(1))
                .andExpect(jsonPath("$.hourlyReservations.length()").value(2))
                .andExpect(jsonPath("$.storeCoupons.downloads").value(3))
                .andExpect(jsonPath("$.storeCoupons.applied").value(1))
                .andExpect(jsonPath("$.storeCoupons.applyRate").value(0.3333))
                .andExpect(jsonPath("$.storeCoupons.expired").value(1))
                .andExpect(jsonPath("$.storeCoupons.expireRate").value(0.3333))
                .andExpect(jsonPath("$.storeCoupons.appliedVisited").value(1))
                .andExpect(jsonPath("$.storeCoupons.visitCompletionRate").value(1.0))
                .andExpect(jsonPath("$.storeCoupons.used").value(1))
                .andExpect(jsonPath("$.associationCoupons").doesNotExist());

        mvc.perform(get(ASSOCIATION_URL).session(login(association)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funnel.clicks").value(4))
                .andExpect(jsonPath("$.funnel.reservations").value(13))
                .andExpect(jsonPath("$.funnel.visits").value(12))
                .andExpect(jsonPath("$.confirmedMoves.count").value(6))
                .andExpect(jsonPath("$.hourlyReservations[0].reservations").value(6))
                // 손님 3명뿐인 쿠폰 묶음은 상인회에게 숨긴다
                .andExpect(jsonPath("$.storeCoupons.belowMinimum").value(true))
                .andExpect(jsonPath("$.storeCoupons.downloads").doesNotExist())
                .andExpect(jsonPath("$.associationCoupons.downloads").value(0))
                .andExpect(jsonPath("$.associationCoupons.applyRate").doesNotExist())
                .andExpect(jsonPath("$..customerId").isEmpty());
    }

    @Test
    void FR_ANL_08_2_클릭_예약_방문은_서로_다른_단계로_센다() throws Exception {
        long c = customer();
        long v = visitor(c);
        click(v, storeA);
        reservation(storeA, c, DAY1, "visited");

        mvc.perform(get(OWNER_URL).session(login(owner)))
                .andExpect(jsonPath("$.funnel.clicks").value(1))
                .andExpect(jsonPath("$.funnel.exposures").value(0))
                .andExpect(jsonPath("$.funnel.reservations").value(1))
                .andExpect(jsonPath("$.funnel.visits").value(1));

        // 다시 눌렀지만 예약은 신청만 하고 취소: 클릭 · 예약만 늘고 방문은 그대로
        click(v, storeA);
        reservation(storeA, c, DAY1.plusDays(2), "canceled");
        mvc.perform(get(OWNER_URL).session(login(owner)))
                .andExpect(jsonPath("$.funnel.clicks").value(2))
                .andExpect(jsonPath("$.funnel.reservations").value(2))
                .andExpect(jsonPath("$.funnel.visits").value(1));
    }

    @Test
    void FR_ANL_09_1_같은_회원이_여러_번_눌러도_발급된_1장만_다운로드_수로_센다() throws Exception {
        // 중복 클릭은 발급 단계(E12 · 30일 제한)에서 1장으로 막히고, 지표는 발급된 쿠폰만 센다. 클릭 기록은 쿠폰 지표에 섞지 않는다
        long c = customer();
        long v = visitor(c);
        for (int i = 0; i < 3; i++) {
            click(v, storeA);
        }
        coupon(couponPolicy(storeA), c, "downloaded", null);

        mvc.perform(get(OWNER_URL).session(login(owner)))
                .andExpect(jsonPath("$.storeCoupons.downloads").value(1))
                .andExpect(jsonPath("$.storeCoupons.applied").value(0))
                .andExpect(jsonPath("$.storeCoupons.visitCompletionRate").doesNotExist());
    }

    @Test
    void 사장님은_사장님_지표만_상인회는_상인회_지표만_본다() throws Exception {
        mvc.perform(get(OWNER_URL).session(login(association))).andExpect(status().isForbidden());
        mvc.perform(get(ASSOCIATION_URL).session(login(owner))).andExpect(status().isForbidden());
        mvc.perform(get(OWNER_URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(ASSOCIATION_URL)).andExpect(status().isUnauthorized());
    }

    private long customer() {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '지표손님', ?)",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        return id;
    }

    private long visitor(Long customerId) {
        jdbc.update("INSERT INTO visitor (anon_id, customer_id) VALUES (?, ?)",
                "xm-" + UUID.randomUUID().toString().substring(3), customerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void click(long visitorId, long storeId) {
        jdbc.update("INSERT INTO store_click (visitor_id, store_id, clicked_at) VALUES (?, ?, ?)", visitorId, storeId, DAY1);
    }

    private void expose(long visitorId, long recommendedStoreId, LocalDateTime shownAt) {
        jdbc.update("""
                INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, badge, shown_at)
                VALUES (?, ?, ?, ?, 1, 'visit_data', ?)
                """, visitorId, UUID.randomUUID().toString(), storeB, recommendedStoreId, shownAt);
    }

    private long reservation(long storeId, long customerId, LocalDateTime start, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, "X" + (System.nanoTime() % 100_000_000L) + "-" + (seq++), storeId, customerId, start, status,
                "canceled".equals(status) ? "customer_canceled" : null, start.minusDays(1),
                "visited".equals(status) ? start.plusHours(1) : null);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long couponPolicy(long storeId) {
        jdbc.update("""
                INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count, confirmed_at)
                VALUES (?, 'fixed_discount', 1000, 10, 3, ?)
                """, storeId, DAY1.minusDays(10));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void coupon(long policyId, long customerId, String status, Long reservationId) {
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status, reservation_id)
                VALUES (?, ?, (SELECT store_id FROM coupon_policy WHERE id = ?), ?, ?, ?, ?)
                """, customerId, policyId, policyId, DAY1.minusDays(3), DAY1.plusDays(4), status, reservationId);
    }

    private long store(String name, String category) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, ?, 'center', 'approved', '10:00', '22:00', 5)
                """, regionId, name, category);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
