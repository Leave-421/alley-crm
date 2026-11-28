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

/** C19 상인회 가게 성과 비교 · 취소율 (FR-ANL-04 · 07). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StorePerformanceApiTest {

    static final String PASSWORD = "password1";
    static final String URL = "/api/v1/association/store-performance";
    /** 2026-09-01 12:00 Asia/Seoul */
    static final LocalDateTime DAY1 = LocalDateTime.of(2026, 9, 1, 3, 0);
    /** 이름 순 정렬: A가 stores[0], B가 stores[1] */
    static final String A = "$.stores[0]";
    static final String B = "$.stores[1]";

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
    int seq;

    @BeforeEach
    void seed() {
        clock.setTo(Instant.parse("2026-11-28T01:00:00Z"));
        jdbc.update("INSERT INTO region (name) VALUES ('성과 비교 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        storeA = store("성과 가게 A");
        storeB = store("성과 가게 B");
        association = "spassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        owner = "spowner" + System.nanoTime();
        accountService.createOwner(storeA, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE f FROM referral_fee f JOIN store s ON s.id = f.receiving_store_id WHERE s.region_id = ?", regionId);
        jdbc.update("UPDATE reservation r JOIN store s ON s.id = r.store_id SET r.course_proposal_id = NULL WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE p FROM course_proposal p JOIN store s ON s.id = p.recommended_store_id WHERE s.region_id = ?", regionId);
        jdbc.update("DELETE r FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?", regionId);
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sp%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_ANL_04_1_가게별_예약_방문_소개_실적_재방문율_수락률() throws Exception {
        // 손님 5명이 A에 왔다가 A 예약의 코스 제안으로 B에 가서 소개 확정, 그중 1명은 10일 뒤 A에 다시 옴
        for (int i = 0; i < 5; i++) {
            long c = customer();
            long base = reservation(storeA, c, DAY1, "visited", null, null);
            long proposal = proposal(base, storeB);
            referral(reservation(storeB, c, DAY1.plusHours(2), "visited", null, proposal), 400);
            if (i == 0) {
                reservation(storeA, c, DAY1.plusDays(10), "visited", null, null);
            }
        }

        mvc.perform(get(URL).session(login(association)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisitDays").value(60))
                .andExpect(jsonPath(A + ".storeId").value(String.valueOf(storeA)))
                .andExpect(jsonPath(A + ".reservations.total").value(6))
                .andExpect(jsonPath(A + ".reservations.visited").value(6))
                .andExpect(jsonPath(A + ".reservations.canceled").value(0))
                .andExpect(jsonPath(A + ".reservations.cancelRate").value(0.0))
                .andExpect(jsonPath(A + ".referral.sent.count").value(5))
                .andExpect(jsonPath(A + ".referral.received.count").value(0))
                .andExpect(jsonPath(A + ".referral.received.feePaid").value(0))
                .andExpect(jsonPath(A + ".revisit.customers").value(5))
                .andExpect(jsonPath(A + ".revisit.revisitedCustomers").value(1))
                .andExpect(jsonPath(A + ".revisit.rate").value(0.2))
                .andExpect(jsonPath(A + ".acceptance.shownProposals").value(5))
                .andExpect(jsonPath(A + ".acceptance.rate").value(1.0))
                .andExpect(jsonPath(B + ".reservations.total").value(5))
                .andExpect(jsonPath(B + ".referral.sent.count").value(0))
                .andExpect(jsonPath(B + ".referral.received.count").value(5))
                .andExpect(jsonPath(B + ".referral.received.feePaid").value(2000))
                .andExpect(jsonPath(B + ".revisit.rate").value(0.0))
                .andExpect(jsonPath(B + ".acceptance.shownProposals").value(0))
                .andExpect(jsonPath(B + ".acceptance.rate").doesNotExist())
                // 상대 가게 · 손님 단위 값은 없다
                .andExpect(jsonPath("$..senderStoreId").isEmpty())
                .andExpect(jsonPath("$..customerId").isEmpty());
    }

    @Test
    void FR_ANL_04_2_손님이_4명인_칸은_5명_미만으로_숨긴다() throws Exception {
        for (int i = 0; i < 5; i++) {
            reservation(storeA, customer(), DAY1, "visited", null, null);
        }
        // B에는 손님 4명, 모두 A에서 소개로 옴
        for (int i = 0; i < 4; i++) {
            long c = customer();
            long proposal = proposal(reservation(storeA, c, DAY1.plusDays(1), "visited", null, null), storeB);
            referral(reservation(storeB, c, DAY1.plusDays(1).plusHours(2), "visited", null, proposal), 400);
        }

        mvc.perform(get(URL).session(login(association)))
                .andExpect(jsonPath(A + ".reservations.belowMinimum").value(false))
                .andExpect(jsonPath(A + ".reservations.total").value(9))
                .andExpect(jsonPath(A + ".referral.sent.belowMinimum").value(true))
                .andExpect(jsonPath(A + ".referral.sent.count").doesNotExist())
                .andExpect(jsonPath(B + ".reservations.belowMinimum").value(true))
                .andExpect(jsonPath(B + ".reservations.total").doesNotExist())
                .andExpect(jsonPath(B + ".reservations.cancelRate").doesNotExist())
                .andExpect(jsonPath(B + ".referral.received.belowMinimum").value(true))
                .andExpect(jsonPath(B + ".referral.received.count").doesNotExist())
                .andExpect(jsonPath(B + ".referral.received.feePaid").doesNotExist())
                .andExpect(jsonPath(B + ".revisit.belowMinimum").value(true));
    }

    @Test
    void FR_ANL_07_1_취소율은_전체와_사유별로_나뉘고_다른_지표에_섞이지_않는다() throws Exception {
        // 손님 10명이 A에 100일 간격으로 4번 방문(재방문 아님) + 각 1건 취소: 손님 6 · 가게 거절 3 · 자동 1
        String[] reasons = {"customer_canceled", "customer_canceled", "customer_canceled", "customer_canceled",
                "customer_canceled", "customer_canceled", "store_rejected", "store_rejected", "store_rejected", "auto_canceled"};
        for (String reason : reasons) {
            long c = customer();
            for (int d = 0; d < 4; d++) {
                reservation(storeA, c, DAY1.plusDays(d * 100L), "visited", null, null);
            }
            // 첫 방문 다음 날 예약: 방문으로 섞이면 재방문이 된다
            long canceled = reservation(storeA, c, DAY1.plusDays(1), "canceled", reason, null);
            proposal(canceled, storeB);
        }
        reservation(storeA, customers.get(0), DAY1.plusDays(500), "confirmed", null, null); // 진행 중: 예약 수에만

        mvc.perform(get(URL).session(login(association)))
                .andExpect(jsonPath(A + ".reservations.total").value(51))
                .andExpect(jsonPath(A + ".reservations.visited").value(40))
                .andExpect(jsonPath(A + ".reservations.canceled").value(10))
                .andExpect(jsonPath(A + ".reservations.cancelRate").value(0.2))
                .andExpect(jsonPath(A + ".reservations.cancelRateByReason.customerCanceled").value(0.12))
                .andExpect(jsonPath(A + ".reservations.cancelRateByReason.storeRejected").value(0.06))
                .andExpect(jsonPath(A + ".reservations.cancelRateByReason.autoCanceled").value(0.02))
                .andExpect(jsonPath(A + ".revisit.customers").value(10))
                .andExpect(jsonPath(A + ".revisit.revisitedCustomers").value(0))
                .andExpect(jsonPath(A + ".acceptance.shownProposals").value(10))
                .andExpect(jsonPath(A + ".acceptance.acceptedProposals").value(0))
                .andExpect(jsonPath(A + ".referral.sent.count").value(0));
    }

    @Test
    void 상인회만_본다() throws Exception {
        mvc.perform(get(URL).session(login(owner))).andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    private long customer() {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '성과손님', ?)",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        return id;
    }

    private long reservation(long storeId, long customerId, LocalDateTime start, String status, String cancelReason,
            Long proposalId) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at, course_proposal_id)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, ?)
                """, "P" + (System.nanoTime() % 100_000_000L) + "-" + (seq++), storeId, customerId, start, status,
                cancelReason, start.minusDays(1), "visited".equals(status) ? start.plusHours(1) : null, proposalId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long proposal(long baseReservationId, long storeId) {
        jdbc.update("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, ?)",
                baseReservationId, storeId, DAY1);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** A가 보내고 B가 받은 소개 확정 */
    private void referral(long reservationId, int fee) {
        jdbc.update("""
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, fee_base, fee_rate,
                                          fee_amount, settlement_month)
                VALUES (?, ?, ?, ?, ?, 0.04, ?, '2026-09')
                """, reservationId, storeB, storeA, fee * 25, fee * 25, fee);
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
