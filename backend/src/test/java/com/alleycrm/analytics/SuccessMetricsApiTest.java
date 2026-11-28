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

/** C18 상인회 성공 지표: 수락률 · 재방문율 · 수수료 총액 (FR-ANL-06). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SuccessMetricsApiTest {

    static final String PASSWORD = "password1";
    /** 예약 시작 시각의 기준일: 2026-09-01 12:00 Asia/Seoul */
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
    long storeA;
    long storeB;
    String association;
    String owner;
    final List<Long> customers = new ArrayList<>();
    final List<Long> visitors = new ArrayList<>();

    @BeforeEach
    void seed() {
        clock.setTo(Instant.parse("2026-11-28T01:00:00Z"));
        jdbc.update("INSERT INTO region (name) VALUES ('성공 지표 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("지표 가게 A");
        storeB = store("지표 가게 B");
        association = "smassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        owner = "smowner" + System.nanoTime();
        accountService.createOwner(storeA, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE f FROM referral_fee f JOIN store s ON s.id = f.receiving_store_id WHERE s.region_id = ?", regionId);
        jdbc.update("UPDATE reservation r JOIN store s ON s.id = r.store_id SET r.course_proposal_id = NULL WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE p FROM course_proposal p JOIN store s ON s.id = p.recommended_store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE r FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE e FROM recommendation_exposure e JOIN store s ON s.id = e.base_store_id WHERE s.region_id = ?", regionId);
        for (long v : visitors) {
            jdbc.update("DELETE FROM visitor WHERE id = ?", v);
        }
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sm%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_ANL_06_1_표시된_제안_200건_중_소개_확정_30건이면_15퍼센트() throws Exception {
        long customer = customer();
        for (int i = 0; i < 200; i++) {
            long base = reservation(storeA, customer, DAY1.plusMinutes(i), "visited", null);
            long proposal = proposal(base, storeB);
            if (i < 30) {
                referral(reservation(storeB, customer, DAY1.plusMinutes(i).plusHours(2), "visited", proposal), "2026-09", 400);
            } else if (i < 40) {
                reservation(storeB, customer, DAY1.plusMinutes(i).plusHours(2), "canceled", proposal); // 신청만 · 취소는 수락 아님
            }
        }

        mvc.perform(get("/api/v1/association/success-metrics").session(login(association)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptance.shownProposals").value(200))
                .andExpect(jsonPath("$.acceptance.acceptedProposals").value(30))
                .andExpect(jsonPath("$.acceptance.rate").value(0.15));
    }

    @Test
    void FR_ANL_06_2_같은_기준_예약_같은_추천_가게는_여러_번_표시돼도_1건이고_수락도_제안당_1번() throws Exception {
        long customer = customer();
        long base = reservation(storeA, customer, DAY1, "visited", null);
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT IGNORE INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, ?)",
                    base, storeB, DAY1);
        }
        long proposal = jdbc.queryForObject("SELECT id FROM course_proposal WHERE base_reservation_id = ?", Long.class, base);
        // 한 제안으로 코스 예약 두 건이 모두 소개 확정돼도 분자는 1
        referral(reservation(storeB, customer, DAY1.plusHours(2), "visited", proposal), "2026-09", 400);
        referral(reservation(storeB, customer, DAY1.plusDays(3), "visited", proposal), "2026-09", 400);

        mvc.perform(get("/api/v1/association/success-metrics").session(login(association)))
                .andExpect(jsonPath("$.acceptance.shownProposals").value(1))
                .andExpect(jsonPath("$.acceptance.acceptedProposals").value(1))
                .andExpect(jsonPath("$.acceptance.rate").value(1.0));
    }

    @Test
    void FR_ANL_06_3_지도_추천_노출만_있고_정식_제안이_없으면_수락률을_표시하지_않는다() throws Exception {
        jdbc.update("INSERT INTO visitor (anon_id) VALUES (?)", UUID.randomUUID().toString());
        long visitor = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        visitors.add(visitor);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, badge, shown_at)
                    VALUES (?, ?, ?, ?, 1, 'local_popular', ?)
                    """, visitor, UUID.randomUUID().toString(), storeA, storeB, DAY1);
        }

        mvc.perform(get("/api/v1/association/success-metrics").session(login(association)))
                .andExpect(jsonPath("$.acceptance.shownProposals").value(0))
                .andExpect(jsonPath("$.acceptance.rate").doesNotExist());
    }

    @Test
    void FR_ANL_06_4_60일_안_다른_날짜에_다시_오면_재방문이고_같은_날_두_번은_아니다() throws Exception {
        visits(storeA, 0, 0, 39);   // 1일 · 1일 · 40일 → 재방문
        visits(storeA, 0, 0);       // 같은 날 두 번만 → 아님
        visits(storeA, 0, 60);      // 60일 뒤 → 재방문(60일 안)
        visits(storeA, 0, 61);      // 61일 뒤 → 아님
        visits(storeA, 0);          // 한 번
        visits(storeB, 0);          // 가게 B는 1명뿐 → 숨김
        long canceledTwice = customer();
        reservation(storeA, canceledTwice, DAY1, "canceled", null);
        reservation(storeA, canceledTwice, DAY1.plusDays(5), "canceled", null); // 취소는 방문이 아님

        mvc.perform(get("/api/v1/association/success-metrics").session(login(association)))
                .andExpect(jsonPath("$.revisit.revisitDays").value(60))
                // 권역: 방문 손님 6명(A 5 + B 1), 재방문 2명
                .andExpect(jsonPath("$.revisit.region.customers").value(6))
                .andExpect(jsonPath("$.revisit.region.revisitedCustomers").value(2))
                .andExpect(jsonPath("$.revisit.region.rate").value(0.3333))
                .andExpect(jsonPath("$.revisit.stores[0].storeName").value("지표 가게 A"))
                .andExpect(jsonPath("$.revisit.stores[0].revisit.customers").value(5))
                .andExpect(jsonPath("$.revisit.stores[0].revisit.revisitedCustomers").value(2))
                .andExpect(jsonPath("$.revisit.stores[0].revisit.rate").value(0.4))
                .andExpect(jsonPath("$.revisit.stores[1].revisit.belowMinimum").value(true))
                .andExpect(jsonPath("$.revisit.stores[1].revisit.customers").doesNotExist());
    }

    @Test
    void 권역_재방문은_가게를_가리지_않고_다른_날_다시_온_손님이다() throws Exception {
        for (int i = 0; i < 5; i++) {
            long c = customer();
            reservation(storeA, c, DAY1, "visited", null);
            reservation(storeB, c, DAY1.plusDays(10), "visited", null);
        }

        mvc.perform(get("/api/v1/association/success-metrics").session(login(association)))
                .andExpect(jsonPath("$.revisit.region.revisitedCustomers").value(5))
                .andExpect(jsonPath("$.revisit.stores[0].revisit.revisitedCustomers").value(0));
    }

    @Test
    void 수수료_총액은_방문_처리된_달_기준이고_기본은_이번_달() throws Exception {
        long customer = customer();
        long base = reservation(storeA, customer, DAY1, "visited", null);
        long p1 = proposal(base, storeB);
        referral(reservation(storeB, customer, DAY1.plusHours(2), "visited", p1), "2026-11", 1200);
        referral(reservation(storeB, customer, DAY1.plusHours(3), "visited", p1), "2026-11", 800);
        referral(reservation(storeB, customer, DAY1.plusHours(4), "visited", p1), "2026-10", 500);

        MockHttpSession session = login(association);
        mvc.perform(get("/api/v1/association/success-metrics").session(session))
                .andExpect(jsonPath("$.referralFee.month").value("2026-11"))
                .andExpect(jsonPath("$.referralFee.amount").value(2000));
        mvc.perform(get("/api/v1/association/success-metrics").param("month", "2026-10").session(session))
                .andExpect(jsonPath("$.referralFee.amount").value(500));
        mvc.perform(get("/api/v1/association/success-metrics").param("month", "2026-13").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 상인회만_본다() throws Exception {
        mvc.perform(get("/api/v1/association/success-metrics").session(login(owner))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/association/success-metrics")).andExpect(status().isUnauthorized());
    }

    /** 새 손님 하나가 첫날로부터 dayOffsets일 뒤에 방문 처리 */
    private void visits(long storeId, int... dayOffsets) {
        long c = customer();
        for (int d : dayOffsets) {
            reservation(storeId, c, DAY1.plusDays(d).plusMinutes(customers.size()), "visited", null);
        }
    }

    private long customer() {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '지표손님', ?)",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        return id;
    }

    private long reservation(long storeId, long customerId, LocalDateTime start, String status, Long proposalId) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at, course_proposal_id)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, ?)
                """, "M" + System.nanoTime() % 1_000_000_000L, storeId, customerId, start, status,
                "canceled".equals(status) ? "customer_canceled" : null, start.minusDays(1),
                "visited".equals(status) ? start.plusHours(1) : null, proposalId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long proposal(long baseReservationId, long storeId) {
        jdbc.update("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, ?)",
                baseReservationId, storeId, DAY1);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void referral(long reservationId, String month, int fee) {
        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, fee_base, fee_rate,
                                          fee_amount, settlement_month)
                VALUES (?, ?, ?, ?, ?, 0.04, ?, ?)
                """, reservationId, storeB, storeA, fee * 25, fee * 25, fee, month);
    }

    private long store(String name) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId, name);
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
