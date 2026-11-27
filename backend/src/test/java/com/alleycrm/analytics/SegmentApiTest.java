package com.alleycrm.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/** C16 사장님 세그먼트: 손님이 이용한 전체 가게 중 우리 가게 비율로 네 묶음, 5명 미만 숨김 없음 (FR-ANL-01). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SegmentApiTest {

    static final String PASSWORD = "password1";
    static final Instant DAY = Instant.parse("2026-11-20T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountService accountService;

    long regionId;
    long mine;
    long other;
    String owner;
    String otherOwner;
    String association;
    final List<Long> customers = new ArrayList<>();
    int seq;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO region (name) VALUES ('세그먼트 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        mine = store("우리 가게");
        other = store("다른 가게");
        owner = "sgowner" + System.nanoTime();
        accountService.createOwner(mine, owner, PASSWORD);
        otherOwner = "sgowner" + System.nanoTime();
        accountService.createOwner(other, otherOwner, PASSWORD);
        association = "sgassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM reservation WHERE store_id IN (SELECT id FROM store WHERE region_id = ?)", regionId);
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sg%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_ANL_01_1_네_묶음으로_나누고_3명인_묶음도_그대로_보인다() throws Exception {
        customer(1, 0);
        customer(1, 0);
        customer(2, 0);          // 우리 가게만 3명 (방문 4)
        customer(1, 1);          // 50% 경계 → 주로
        customer(2, 1);          // 2/3 → 주로
        customer(1, 3);          // 25% 경계 → 가끔
        customer(1, 4);          // 20% → 드물게
        customer(0, 3);          // 우리 가게 방문 없음 → 빠짐
        long canceledHere = customer(0, 2);
        visit(mine, canceledHere, "canceled"); // 우리 가게 취소는 방문이 아님 → 빠짐

        mvc.perform(get("/api/v1/owner/segments").session(login(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").value(7))
                .andExpect(jsonPath("$.segments[*].segment").value(Matchers.contains("exclusive", "mostly", "sometimes", "rarely")))
                .andExpect(jsonPath("$.segments[*].customerCount").value(Matchers.contains(3, 2, 1, 1)))
                .andExpect(jsonPath("$.segments[*].visitCount").value(Matchers.contains(4, 3, 1, 1)));
    }

    @Test
    void 가게마다_자기_손님_기준이고_상인회는_볼_수_없다() throws Exception {
        customer(1, 3);

        // 다른 가게 기준으로는 3/4 → 주로
        mvc.perform(get("/api/v1/owner/segments").session(login(otherOwner)))
                .andExpect(jsonPath("$.totalCustomers").value(1))
                .andExpect(jsonPath("$.segments[1].customerCount").value(1));
        mvc.perform(get("/api/v1/owner/segments").session(login(association))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/owner/segments")).andExpect(status().isUnauthorized());
    }

    @Test
    void 손님이_없으면_네_묶음이_모두_0() throws Exception {
        mvc.perform(get("/api/v1/owner/segments").session(login(owner)))
                .andExpect(jsonPath("$.totalCustomers").value(0))
                .andExpect(jsonPath("$.segments.length()").value(4))
                .andExpect(jsonPath("$.segments[*].customerCount").value(Matchers.everyItem(Matchers.is(0))));
    }

    /** 우리 가게 mineVisits번, 다른 가게 otherVisits번 방문 처리한 손님 */
    private long customer(int mineVisits, int otherVisits) {
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '세그먼트손님', ?)",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        for (int i = 0; i < mineVisits; i++) {
            visit(mine, id, "visited");
        }
        for (int i = 0; i < otherVisits; i++) {
            visit(other, id, "visited");
        }
        return id;
    }

    private void visit(long storeId, long customerId, String status) {
        Instant start = DAY.plusSeconds(86_400L * seq++);
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, "S" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(start), status,
                "canceled".equals(status) ? "customer_canceled" : null, utc(start.minusSeconds(86_400)),
                "visited".equals(status) ? utc(start.plusSeconds(3600)) : null);
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

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
