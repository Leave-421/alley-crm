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

/** C17 사장님 손님 흐름: 우리 가게로 들어오고 나가는 이동, 5명 미만 숨김 (FR-ANL-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerFlowApiTest {

    static final String PASSWORD = "password1";
    /** 2026-11-20 10:00 KST */
    static final Instant DAY = Instant.parse("2026-11-20T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccountService accountService;

    long regionId;
    long mine;
    long b;
    long c;
    long d;
    String owner;
    String otherOwner;
    String association;
    final List<Long> customers = new ArrayList<>();

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO region (name) VALUES ('손님 흐름 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        mine = store("우리 가게", "approved");
        b = store("흐름 B", "approved");
        c = store("흐름 C", "approved");
        d = store("흐름 D", "withdrawn");
        owner = "cfowner" + System.nanoTime();
        accountService.createOwner(mine, owner, PASSWORD);
        otherOwner = "cfowner" + System.nanoTime();
        accountService.createOwner(b, otherOwner, PASSWORD);
        association = "cfassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM reservation WHERE store_id IN (SELECT id FROM store WHERE region_id = ?)", regionId);
        for (long cu : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", cu);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'cf%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_ANL_02_1_우리_가게에서_C로_간_손님_3명은_5명_미만() throws Exception {
        moves(mine, c, 3);
        moves(mine, b, 6);
        moves(b, mine, 5);
        moves(c, b, 7); // 우리 가게와 상관없는 이동은 안 보인다

        mvc.perform(get("/api/v1/owner/customer-flows").session(login(owner)))
                .andExpect(status().isOk())
                // 나감: 보이는 묶음이 먼저, 숨긴 묶음은 뒤
                .andExpect(jsonPath("$.outbound[*].storeId").value(Matchers.contains(String.valueOf(b), String.valueOf(c))))
                .andExpect(jsonPath("$.outbound[0].storeName").value("흐름 B"))
                .andExpect(jsonPath("$.outbound[0].moves.count").value(6))
                .andExpect(jsonPath("$.outbound[1].moves.count").isEmpty())
                .andExpect(jsonPath("$.outbound[1].moves.belowMinimum").value(true))
                // 들어옴
                .andExpect(jsonPath("$.inbound.length()").value(1))
                .andExpect(jsonPath("$.inbound[0].storeId").value(String.valueOf(b)))
                .andExpect(jsonPath("$.inbound[0].moves.count").value(5));
    }

    @Test
    void 탈퇴한_가게는_빠지고_다른_가게_사장님은_자기_가게_흐름만_본다() throws Exception {
        moves(mine, d, 6);
        moves(b, c, 6);

        mvc.perform(get("/api/v1/owner/customer-flows").session(login(owner)))
                .andExpect(jsonPath("$.outbound").isEmpty())
                .andExpect(jsonPath("$.inbound").isEmpty());
        mvc.perform(get("/api/v1/owner/customer-flows").session(login(otherOwner)))
                .andExpect(jsonPath("$.outbound[0].storeId").value(String.valueOf(c)))
                .andExpect(jsonPath("$.outbound[0].moves.count").value(6));
    }

    @Test
    void 사장님만() throws Exception {
        mvc.perform(get("/api/v1/owner/customer-flows").session(login(association))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/owner/customer-flows")).andExpect(status().isUnauthorized());
    }

    /** n명의 손님이 같은 날 from(10시) → to(12시)를 연달아 방문 처리 */
    private void moves(long from, long to, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '흐름손님', ?)",
                    "010" + (System.nanoTime() % 100_000_000L));
            long customer = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            customers.add(customer);
            visit(from, customer, DAY);
            visit(to, customer, DAY.plusSeconds(7200));
        }
    }

    private void visit(long storeId, long customerId, Instant start) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, visited_at)
                VALUES (?, ?, ?, ?, 2, 'visited', ?, ?)
                """, "G" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(start),
                utc(start.minusSeconds(86_400)), utc(start.plusSeconds(3600)));
    }

    private long store(String name, String joinStatus) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, ?, 'cafe', 'center', ?, '10:00', '22:00', 5)
                """, regionId, name, joinStatus);
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
