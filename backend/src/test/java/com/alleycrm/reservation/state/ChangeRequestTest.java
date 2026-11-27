package com.alleycrm.reservation.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
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

/** D8 예약 변경 · 재승인 · 변경 자동 거절(FR-RSV-07 #1 ~ #4, 기획 11-27 예약금 차액 · 기한). 인원당 예약금 10,000원. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChangeRequestTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    /** 원래 예약 2026-11-29 18:00 서울 */
    static final String START = "2026-11-29 09:00:00";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ReservationStateService states;

    long store;
    long member;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot, deposit_per_person, cancel_deadline_hours)"
                + " VALUES (" + region + ", 'A', 'cafe', 'center', 'approved', '10:00', '22:00', 5, 10000, 3)");
        long acc = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('m1', 'x', 'customer')");
        member = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + acc + ")");
        jdbc.update("INSERT INTO wallet (owner_type, owner_id, balance) VALUES ('customer', ?, 50000)", member);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (String t : new String[] {"wallet_tx", "wallet", "reservation", "customer", "account", "store", "region"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void FR_RSV_07_1_변경하면_신청이_되고_원래_값은_그대로_바꿀_값이_대기() throws Exception {
        long r = reservation(2);
        change(r, "2026-11-29T10:00:00Z", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.startAt").value("2026-11-29T09:00:00Z"))
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.pendingStartAt").value("2026-11-29T10:00:00Z"))
                .andExpect(jsonPath("$.pendingPartySize").value(3));
        assertThat(balance()).isEqualTo(50000); // 신청 때는 돈이 움직이지 않는다

        // 사장님 상세 · 목록에도 변경 대기 값이 보인다(B7 O-02)
        mvc.perform(get("/api/v1/owner/reservations/" + r).with(user(ownerLogin())))
                .andExpect(jsonPath("$.startAt").value("2026-11-29T09:00:00Z"))
                .andExpect(jsonPath("$.pendingStartAt").value("2026-11-29T10:00:00Z"))
                .andExpect(jsonPath("$.pendingPartySize").value(3))
                .andExpect(jsonPath("$.expectedArrivalAt").doesNotExist());
        mvc.perform(get("/api/v1/owner/reservations").param("date", "2026-11-29").with(user(ownerLogin())))
                .andExpect(jsonPath("$.items[0].pendingStartAt").value("2026-11-29T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].pendingPartySize").value(3));
    }

    @Test
    void FR_RSV_07_2_승인하면_바꾼_값으로_확정되고_늘어난_인원만큼_더_낸다() throws Exception {
        long r = reservation(2);
        change(r, "2026-11-29T10:00:00Z", 3).andExpect(status().isOk());

        states.confirm(store, r);

        Map<String, Object> row = row(r);
        assertThat(row.get("status")).isEqualTo("confirmed");
        assertThat(row.get("party_size")).isEqualTo(3);
        assertThat(row.get("deposit_amount")).isEqualTo(30000);
        assertThat(row.get("pending_start_at")).isNull();
        assertThat(balance()).isEqualTo(40000);
    }

    @Test
    void 인원이_줄면_승인_때_차액을_돌려받는다() throws Exception {
        long r = reservation(3);
        change(r, null, 2).andExpect(status().isOk());
        states.confirm(store, r);
        assertThat(balance()).isEqualTo(60000);
        assertThat(row(r).get("deposit_amount")).isEqualTo(20000);
    }

    @Test
    void FR_RSV_07_3_거절하면_원래_값으로_확정_유지_돈은_그대로() throws Exception {
        long r = reservation(2);
        change(r, "2026-11-29T10:00:00Z", 3).andExpect(status().isOk());

        states.reject(store, r);

        Map<String, Object> row = row(r);
        assertThat(row.get("status")).isEqualTo("confirmed");
        assertThat(row.get("party_size")).isEqualTo(2);
        assertThat(row.get("pending_start_at")).isNull();
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(balance()).isEqualTo(50000);
    }

    @Test
    void FR_RSV_07_4_원래_시각과_바뀐_시각_중_이른_쪽까지_응답이_없으면_변경만_자동_거절() throws Exception {
        long r = reservation(2);
        change(r, "2026-11-29T05:00:00Z", null).andExpect(status().isOk()); // 바뀐 시각(14:00 서울)이 더 이름

        clock.setTo(Instant.parse("2026-11-29T05:00:00Z"));
        assertThat(states.autoRejectDue()).isEqualTo(1);

        Map<String, Object> row = row(r);
        assertThat(row.get("status")).isEqualTo("confirmed");
        assertThat(row.get("pending_start_at")).isNull();
    }

    @Test
    void 인원이_늘어_잔액이_모자라면_변경_신청_거절() throws Exception {
        jdbc.update("UPDATE wallet SET balance = 5000 WHERE owner_id = ?", member);
        long r = reservation(2);
        change(r, null, 3).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("insufficient_balance"));
    }

    @Test
    void 승인_때_잔액이_모자라면_승인_거절_변경은_대기_그대로() throws Exception {
        long r = reservation(2);
        change(r, null, 4).andExpect(status().isOk());
        jdbc.update("UPDATE wallet SET balance = 0 WHERE owner_id = ?", member);

        mvc.perform(post("/api/v1/owner/reservations/" + r + "/confirm").with(TestCsrf.csrf(mvc)).with(user(ownerLogin())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("insufficient_balance"));
        assertThat(row(r).get("status")).isEqualTo("requested");
        assertThat(row(r).get("pending_party_size")).isEqualTo(4);
    }

    @Test
    void 가게_취소_마감이_지나면_변경_신청_거절() throws Exception {
        long r = reservation(2);
        clock.setTo(Instant.parse("2026-11-29T07:00:00Z")); // 예약 2시간 전, 마감(3시간 전) 지남
        change(r, null, 3).andExpect(status().isConflict());
    }

    @Test
    void 영업시간_밖이나_자리가_없는_시각으로는_변경할_수_없다() throws Exception {
        long r = reservation(2);
        change(r, "2026-11-29T14:00:00Z", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("slot_unavailable")); // 23:00 서울
    }

    private String ownerLogin() {
        jdbc.update("INSERT IGNORE INTO account (login_id, password_hash, role, store_id) VALUES ('owner1', 'x', 'owner', ?)", store);
        return "owner1";
    }

    private ResultActions change(long id, String startAt, Integer party) throws Exception {
        String body = "{" + (startAt == null ? "" : "\"startAt\": \"" + startAt + "\"") + (startAt != null && party != null ? ", " : "")
                + (party == null ? "" : "\"partySize\": " + party) + "}";
        return mvc.perform(post("/api/v1/reservations/" + id + "/change").with(TestCsrf.csrf(mvc)).with(user("m1"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long reservation(int party) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at, deposit_amount)"
                + " VALUES ('" + java.util.UUID.randomUUID().toString().substring(0, 8) + "', " + store + ", " + member + ", '" + START
                + "', " + party + ", 'confirmed', '2026-11-20 00:00:00', " + (party * 10000) + ")");
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM reservation WHERE id = ?", id);
    }

    private long balance() {
        return jdbc.queryForObject("SELECT balance FROM wallet WHERE owner_type = 'customer' AND owner_id = ?", Long.class, member);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
