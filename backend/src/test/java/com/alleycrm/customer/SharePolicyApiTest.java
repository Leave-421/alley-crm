package com.alleycrm.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** C11 상인회 정보 공유 설정 (FR-CUS-06). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SharePolicyApiTest {

    static final String PASSWORD = "password1";
    /** 2026-11-27 10:00 Asia/Seoul */
    static final Instant NOW = Instant.parse("2026-11-27T01:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;
    @Autowired
    AppClock clock;

    long regionId;
    long otherRegionId;
    long storeId;
    String association;
    String otherAssociation;
    String owner;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        regionId = region("공유 설정 권역");
        otherRegionId = region("공유 설정 다른 권역");
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '공유 설정 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        association = "spassoc" + System.nanoTime();
        accountService.createAssociation(regionId, association, PASSWORD);
        otherAssociation = "spassoc" + System.nanoTime();
        accountService.createAssociation(otherRegionId, otherAssociation, PASSWORD);
        owner = "spowner" + System.nanoTime();
        accountService.createOwner(storeId, owner, PASSWORD);
    }

    @AfterEach
    void clean() {
        clock.reset();
        List<Long> customers = jdbc.queryForList("SELECT DISTINCT customer_id FROM reservation WHERE store_id = ?", Long.class, storeId);
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM share_consent WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)", storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        for (long c : customers) {
            jdbc.update("DELETE FROM visitor WHERE customer_id = ?", c);
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM share_policy WHERE region_id IN (?, ?)", regionId, otherRegionId);
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'sp%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id IN (?, ?)", regionId, otherRegionId);
    }

    @Test
    void 처음에는_여섯_항목이_모두_켜져_있고_필수_항목이_표시된다() throws Exception {
        mvc.perform(get("/api/v1/association/share-policy").session(login(association)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].item").value(Matchers.contains(
                        "name", "phone", "visited_stores", "party_size", "visit_datetime", "gender_age_group")))
                .andExpect(jsonPath("$.items[*].enabled").value(Matchers.everyItem(Matchers.is(true))))
                .andExpect(jsonPath("$.items[*].required").value(Matchers.contains(true, true, true, false, false, false)));
    }

    @Test
    void FR_CUS_06_1_성별_연령대를_끄면_저장되고_그_뒤_예약의_공유_항목에서_빠진다() throws Exception {
        change(login(association), "gender_age_group", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[5].item").value("gender_age_group"))
                .andExpect(jsonPath("$.items[5].enabled").value(false));

        long reservationId = reserve();
        assertThat(json.readValue(jdbc.queryForObject("SELECT items FROM share_consent WHERE reservation_id = ?", String.class,
                reservationId), List.class))
                .containsExactly("name", "phone", "visited_stores", "party_size", "visit_datetime");

        // 다시 켤 수 있고, 다른 권역에는 영향이 없다
        mvc.perform(get("/api/v1/association/share-policy").session(login(otherAssociation)))
                .andExpect(jsonPath("$.items[5].enabled").value(true));
        change(login(association), "gender_age_group", true).andExpect(jsonPath("$.items[5].enabled").value(true));
    }

    @Test
    void FR_CUS_06_2_3_이름_연락처_방문한_가게_목록은_끌_수_없다() throws Exception {
        MockHttpSession session = login(association);
        for (String required : List.of("name", "phone", "visited_stores")) {
            change(session, required, false)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("conflict"));
        }
        change(session, "name", true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_policy WHERE region_id = ?", Integer.class, regionId)).isZero();
    }

    @Test
    void 입력_역할_로그인_검사() throws Exception {
        MockHttpSession session = login(association);
        change(session, "email", false).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/association/share-policy/party_size").session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/association/share-policy").session(login(owner))).andExpect(status().isForbidden());
        change(login(owner), "party_size", false).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/association/share-policy")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/association/share-policy/party_size").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_policy WHERE region_id = ?", Integer.class, regionId)).isZero();
    }

    private ResultActions change(MockHttpSession session, String item, boolean enabled) throws Exception {
        return mvc.perform(put("/api/v1/association/share-policy/{item}", item).session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"));
    }

    /** 비회원 예약 신청(D2) → 공유 동의 기록(C8, 단계 82) */
    private long reserve() throws Exception {
        String body = mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeId", String.valueOf(storeId),
                                "startAt", "2026-11-28T09:00:00Z", "partySize", 2, "shareConsent", true,
                                "guest", Map.of("name", "비회원", "phone", "01033334444")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.readTree(body).get("id").asString());
    }

    private long region(String name) {
        jdbc.update("INSERT INTO region (name) VALUES (?)", name);
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
