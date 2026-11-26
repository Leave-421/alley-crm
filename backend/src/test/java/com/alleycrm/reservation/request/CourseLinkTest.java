package com.alleycrm.reservation.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
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
import tools.jackson.databind.ObjectMapper;

/** D12 다음 코스 예약(FR-RSV-11 #1 ~ #9, 기획 11-26 (2)). 비회원은 같은 전화번호면 같은 손님이다(C4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CourseLinkTest {

    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");
    /** 기준 예약: 2026-11-27 18:00 Asia/Seoul */
    static final String BASE_START = "2026-11-27T09:00:00Z";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    ObjectMapper json;

    long storeA;
    long storeB;
    long base;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        long region = insert("INSERT INTO region (name) VALUES ('권역')");
        storeA = store(region, "A");
        storeB = store(region, "B");
        base = id(request(storeA, BASE_START, null, null, "01012345678").andExpect(status().isCreated()));
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("UPDATE reservation SET course_proposal_id = NULL, course_root_id = NULL");
        for (String t : new String[] {"share_consent", "wallet_tx", "course_proposal", "reservation", "visitor", "customer", "store", "region"}) {
            try {
                jdbc.update("DELETE FROM " + t);
            } catch (Exception ignored) {
                // 없는 테이블 · 행은 건너뛴다
            }
        }
    }

    @Test
    void FR_RSV_11_1_기준_예약이_없거나_남의_것이면_거절() throws Exception {
        request(storeB, "2026-11-27T11:00:00Z", 999999L, null, "01012345678").andExpect(status().isNotFound());
        request(storeB, "2026-11-27T11:00:00Z", base, null, "01099999999").andExpect(status().isNotFound());
    }

    @Test
    void FR_RSV_11_2_같은_날은_기준_예약보다_늦은_시간만_제안() throws Exception {
        mvc.perform(get("/api/v1/stores/" + storeB + "/available-slots").param("date", "2026-11-27").param("after", BASE_START))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].startAt").value("2026-11-27T09:30:00Z")); // 18:30부터
        mvc.perform(get("/api/v1/stores/" + storeB + "/available-slots").param("date", "2026-11-28").param("after", BASE_START))
                .andExpect(jsonPath("$.slots[0].startAt").value("2026-11-28T01:00:00Z")); // 다른 날은 10:00부터 모두
    }

    @Test
    void FR_RSV_11_3_신청_상태_기준_예약에_코스로_연결() throws Exception {
        request(storeB, "2026-11-27T11:00:00Z", base, null, "01012345678")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseRootId").value(String.valueOf(base)))
                .andExpect(jsonPath("$.fromRecommendation").value(false));
    }

    @Test
    void FR_RSV_11_4_겹침은_보지_않고_늦기만_하면_연결() throws Exception {
        // 기준 18:00, 다음 코스 같은 날 18:30(11-26 (2))
        request(storeB, "2026-11-27T09:30:00Z", base, null, "01012345678").andExpect(status().isCreated());
    }

    @Test
    void FR_RSV_11_5_일반_예약은_시간_제한_없이_코스로_연결되지_않음() throws Exception {
        request(storeB, "2026-11-27T08:00:00Z", null, null, "01012345678")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseRootId").doesNotExist());
    }

    @Test
    void FR_RSV_11_6_8_같은_날_기준보다_이르거나_같으면_거절() throws Exception {
        request(storeB, "2026-11-27T07:00:00Z", base, null, "01012345678")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("conflict"));
        request(storeB, BASE_START, base, null, "01012345678").andExpect(status().isConflict());
    }

    @Test
    void FR_RSV_11_7_다른_날은_모든_시간() throws Exception {
        request(storeB, "2026-11-28T03:00:00Z", base, null, "01012345678").andExpect(status().isCreated()); // 11-28 12:00
    }

    @Test
    void FR_RSV_11_9_취소된_기준_예약으로는_새_다음_코스_거절() throws Exception {
        jdbc.update("UPDATE reservation SET status = 'canceled', cancel_reason = 'store_rejected' WHERE id = ?", base);
        request(storeB, "2026-11-27T11:00:00Z", base, null, "01012345678").andExpect(status().isConflict());
    }

    @Test
    void 정식_제안으로_마감_전에_신청하면_소개_후보() throws Exception {
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeB + ", NOW(6))");
        long r = id(request(storeB, "2026-11-28T03:00:00Z", base, proposal, "01012345678")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromRecommendation").value(true))
                .andExpect(jsonPath("$.senderStoreId").doesNotExist()));
        assertThat(jdbc.queryForObject("SELECT sender_store_id FROM reservation WHERE id = ?", Long.class, r)).isEqualTo(storeA);
    }

    @Test
    void 기준_예약일이_지나서_신청하면_코스로만_연결되고_소개_아님() throws Exception {
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeB + ", NOW(6))");
        clock.setTo(Instant.parse("2026-11-27T15:30:00Z")); // 11-28 00:30 서울: 마감 지남
        long r = id(request(storeB, "2026-11-28T03:00:00Z", base, proposal, "01012345678")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseRootId").value(String.valueOf(base)))
                .andExpect(jsonPath("$.fromRecommendation").value(false)));
        assertThat(jdbc.queryForObject("SELECT sender_store_id FROM reservation WHERE id = ?", Long.class, r)).isNull();
    }

    @Test
    void 다른_가게_제안이면_거절() throws Exception {
        long storeC = store(jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeA), "C");
        long proposal = insert("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES ("
                + base + ", " + storeC + ", NOW(6))");
        request(storeB, "2026-11-28T03:00:00Z", base, proposal, "01012345678").andExpect(status().isConflict());
    }

    private long store(long region, String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)"
                + " VALUES (" + region + ", '" + name + "', 'cafe', 'center', 'approved', '10:00', '22:00', 10)");
    }

    private ResultActions request(long store, String startAt, Long rootId, Long proposalId, String phone) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(store));
        b.put("startAt", startAt);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("guest", Map.of("name", "손님", "phone", phone));
        b.put("courseRootId", rootId == null ? null : String.valueOf(rootId));
        b.put("courseProposalId", proposalId == null ? null : String.valueOf(proposalId));
        return mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private long id(ResultActions r) throws Exception {
        return Long.parseLong(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asString());
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
