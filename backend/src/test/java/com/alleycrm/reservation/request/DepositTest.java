package com.alleycrm.reservation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** D10 예약금 · 가상 충전 · 잔액 부족 거절(FR-RSV-08 #1 ~ #5, FR-STL-06 #2) · 거절 시 환불(FR-RSV-03 #2 · #3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    /** 2026-11-28 18:00 Asia/Seoul */
    static final String START = "2026-11-28T09:00:00Z";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ObjectMapper json;
    @Autowired
    ReservationStateService states;

    long region;
    long storeId;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeId = insert("INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot, deposit_per_person)"
                + " VALUES (" + region + ", '가게', 'cafe', 'center', 'approved', '10:00', '22:00', 10, 10000)");
        long memberAccount = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('member1', 'x', 'customer')");
        // 회원 가입(C2)은 손님 행을 같이 만든다
        jdbc.update("INSERT INTO customer (type, account_id) VALUES ('member', ?)", memberAccount);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", storeId);
        jdbc.update("INSERT INTO account (login_id, password_hash, role, region_id) VALUES ('assoc1', 'x', 'association', ?)", region);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"wallet_tx", "wallet", "share_consent", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_08_1_예약금_0원_가게는_납부_기록이_없다() throws Exception {
        jdbc.update("UPDATE store SET deposit_per_person = 0 WHERE id = ?", storeId);
        guestRequest(3).andExpect(status().isCreated()).andExpect(jsonPath("$.depositAmount").value(0));
        assertThat(count("SELECT COUNT(*) FROM wallet_tx")).isZero();
    }

    @Test
    void FR_RSV_08_2_회원_가상_충전() throws Exception {
        charge("member1", 50000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("customer"))
                .andExpect(jsonPath("$.balance").value(50000));
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE type = 'charge' AND amount = 50000")).isEqualTo(1);
    }

    @Test
    void FR_RSV_08_3_회원_예약금은_인원당_금액_곱하기_인원만큼_잔액에서_빠진다() throws Exception {
        charge("member1", 50000).andExpect(status().isOk());

        memberRequest(3).andExpect(status().isCreated()).andExpect(jsonPath("$.depositAmount").value(30000));

        assertThat(balanceOf("member1")).isEqualTo(20000);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE type = 'deposit_paid' AND amount = -30000 AND reservation_id IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void FR_RSV_08_4_비회원은_전화번호에_묶인_납부_환불_기록() throws Exception {
        long r = id(guestRequest(2).andExpect(status().isCreated()));
        jdbc.update("UPDATE reservation SET status = 'requested' WHERE id = ?", r);

        states.reject(storeId, r);

        assertThat(count("SELECT COUNT(*) FROM wallet")).isZero();
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE guest_phone = '01012345678' AND type = 'deposit_paid' AND amount = -20000")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE guest_phone = '01012345678' AND type = 'deposit_refunded' AND amount = 20000")).isEqualTo(1);
    }

    @Test
    void FR_RSV_08_5_잔액이_예약금보다_적으면_거절하고_아무것도_남지_않는다() throws Exception {
        charge("member1", 20000).andExpect(status().isOk());

        memberRequest(3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("insufficient_balance"));

        assertThat(balanceOf("member1")).isEqualTo(20000);
        assertThat(count("SELECT COUNT(*) FROM reservation")).isZero();
    }

    @Test
    void 가게_거절하면_회원_예약금_100퍼센트_환불_두_번_불려도_한_번() throws Exception {
        charge("member1", 50000).andExpect(status().isOk());
        long r = id(memberRequest(2).andExpect(status().isCreated()));

        states.reject(storeId, r);
        assertThat(balanceOf("member1")).isEqualTo(50000);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE type = 'deposit_refunded'")).isEqualTo(1);
    }

    @Test
    void 자동_취소는_환불하지_않는다() throws Exception {
        charge("member1", 50000).andExpect(status().isOk());
        long r = id(memberRequest(2).andExpect(status().isCreated()));
        jdbc.update("UPDATE reservation SET status = 'confirmed' WHERE id = ?", r);

        clock.setTo(Instant.parse("2026-12-01T01:00:00Z")); // 예약일(11-28) 다음 날이 끝남
        assertThat(states.autoCancelDue()).isEqualTo(1);

        assertThat(balanceOf("member1")).isEqualTo(30000);
        assertThat(count("SELECT COUNT(*) FROM wallet_tx WHERE type = 'deposit_refunded'")).isZero();
    }

    @Test
    void FR_STL_06_2_사장님_가게_가상_충전() throws Exception {
        charge("owner1", 100000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("store"))
                .andExpect(jsonPath("$.balance").value(100000))
                .andExpect(jsonPath("$.needsCharge").value(false));
    }

    @Test
    void 상인회_기금은_충전할_수_없고_범위_밖_금액은_거절() throws Exception {
        charge("assoc1", 10000).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("forbidden_role"));
        charge("member1", 999).andExpect(status().isBadRequest());
        charge("member1", 1_000_001).andExpect(status().isBadRequest());
    }

    private ResultActions charge(String login, int amount) throws Exception {
        return mvc.perform(post("/api/v1/wallets/me/charge").with(TestCsrf.csrf(mvc)).with(user(login))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": " + amount + "}"));
    }

    private ResultActions memberRequest(int party) throws Exception {
        return request(post("/api/v1/reservations").with(user("member1")), party, null);
    }

    private ResultActions guestRequest(int party) throws Exception {
        return request(post("/api/v1/reservations"), party, Map.of("name", "손님", "phone", "01012345678"));
    }

    private ResultActions request(MockHttpServletRequestBuilder req, int party, Map<String, Object> guest) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(storeId));
        b.put("startAt", START);
        b.put("partySize", party);
        b.put("shareConsent", true);
        b.put("guest", guest);
        return mvc.perform(req.with(TestCsrf.csrf(mvc)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private long id(ResultActions r) throws Exception {
        return Long.parseLong(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asString());
    }

    private long balanceOf(String login) {
        Long b = jdbc.queryForObject("""
                SELECT COALESCE(MAX(w.balance), 0) FROM account a JOIN customer c ON c.account_id = a.id
                LEFT JOIN wallet w ON w.owner_type = 'customer' AND w.owner_id = c.id WHERE a.login_id = ?
                """, Long.class, login);
        return b == null ? 0 : b;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
