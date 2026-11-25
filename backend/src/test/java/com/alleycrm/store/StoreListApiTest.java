package com.alleycrm.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** C6 권역의 참여 가게 목록 · 상인회 위치 수정 (FR-STO-02). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StoreListApiTest {

    static final String PASSWORD = "password1";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;

    long regionId;
    long otherRegionId;
    String associationLogin;
    String otherAssociationLogin;

    @BeforeEach
    void seed() {
        regionId = region("목록 테스트 권역");
        otherRegionId = region("다른 권역");
        associationLogin = "assoc" + System.nanoTime();
        accountService.createAssociation(regionId, associationLogin, PASSWORD);
        otherAssociationLogin = "assoc" + System.nanoTime();
        accountService.createAssociation(otherRegionId, otherAssociationLogin, PASSWORD);
    }

    @AfterEach
    void clean() {
        for (long r : List.of(regionId, otherRegionId)) {
            jdbc.update("DELETE FROM account WHERE region_id = ? OR store_id IN (SELECT id FROM store WHERE region_id = ?)", r, r);
            jdbc.update("DELETE FROM store WHERE region_id = ?", r);
            jdbc.update("DELETE FROM region WHERE id = ?", r);
        }
    }

    @Test
    void 참여_가게만_ID순으로_예약_가능_여부와_함께_로그인_없이_본다() throws Exception {
        long open = store(regionId, "approved", "open", true, false);
        long paused = store(regionId, "approved", "paused", true, false);
        long stopped = store(regionId, "approved", "open", false, false);
        long leaving = store(regionId, "withdraw_requested", "open", true, false);
        store(regionId, "requested", "open", true, false);
        store(regionId, "rejected", "open", true, false);
        store(regionId, "withdrawn", "open", true, false);
        store(otherRegionId, "approved", "open", true, false);

        mvc.perform(get("/api/v1/stores").param("regionId", String.valueOf(regionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(
                        String.valueOf(open), String.valueOf(paused), String.valueOf(stopped), String.valueOf(leaving))))
                .andExpect(jsonPath("$.items[0].reservable").value(true))
                .andExpect(jsonPath("$.items[1].reservable").value(false))
                .andExpect(jsonPath("$.items[2].reservable").value(false))
                .andExpect(jsonPath("$.items[2].reservationEnabled").value(false))
                .andExpect(jsonPath("$.items[3].joinStatus").value("withdraw_requested"))
                .andExpect(jsonPath("$.items[0].openTime").value("10:00"))
                .andExpect(jsonPath("$.items[0].closedDays[0]").value("mon"))
                .andExpect(jsonPath("$.items[0].mapX").value(30.5))
                .andExpect(jsonPath("$.items[0].address").value("중앙길 1"));
    }

    @Test
    void 실제_좌표는_검증된_가게만_보인다() throws Exception {
        store(regionId, "approved", "open", true, true);
        store(regionId, "approved", "open", true, false);

        mvc.perform(get("/api/v1/stores").param("regionId", String.valueOf(regionId)))
                .andExpect(jsonPath("$.items[0].realLat").value(37.5))
                .andExpect(jsonPath("$.items[1].realLat").isEmpty())
                .andExpect(jsonPath("$.items[1].realLng").isEmpty());
    }

    @Test
    void 없는_권역은_404_권역이_없거나_잘못되면_400() throws Exception {
        mvc.perform(get("/api/v1/stores").param("regionId", "999999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stores")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stores").param("regionId", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stores").param("regionId", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void 상인회는_자기_권역_가게의_주소_골목_지도_좌표를_고친다() throws Exception {
        long storeId = store(regionId, "approved", "open", true, false);

        location(storeId, login(associationLogin), Map.of("address", "뒷길 9", "alley", "back_street", "mapX", 120.25, "mapY", 200))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("뒷길 9"))
                .andExpect(jsonPath("$.alley").value("back_street"))
                .andExpect(jsonPath("$.mapX").value(120.25));
        assertThat(jdbc.queryForMap("SELECT address, alley, map_x FROM store WHERE id = ?", storeId))
                .containsEntry("address", "뒷길 9").containsEntry("alley", "back_street");
    }

    @Test
    void FR_STO_02_3_사장님은_주소_지도_위치를_못_바꾼다() throws Exception {
        long storeId = store(regionId, "approved", "open", true, false);
        String owner = "o" + System.nanoTime();
        accountService.createOwner(storeId, owner, PASSWORD);

        location(storeId, login(owner), Map.of("address", "다른 곳", "alley", "center"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        assertThat(jdbc.queryForObject("SELECT address FROM store WHERE id = ?", String.class, storeId)).isEqualTo("중앙길 1");
    }

    @Test
    void 다른_권역_가게는_404_잘못된_값은_400_로그인_없으면_401() throws Exception {
        long storeId = store(regionId, "approved", "open", true, false);

        location(storeId, login(otherAssociationLogin), Map.of("alley", "center")).andExpect(status().isNotFound());
        MockHttpSession assoc = login(associationLogin);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("alley", "nowhere"),
                Map.of(),
                Map.of("alley", "center", "mapX", 1000),
                Map.of("alley", "center", "mapX", 1.23456),
                Map.of("alley", "center", "address", "가".repeat(201)))) {
            location(storeId, assoc, bad).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/stores/{id}/location", storeId).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alley\":\"center\"}"))
                .andExpect(status().isUnauthorized());
    }

    private long store(long region, String joinStatus, String businessStatus, boolean reservationEnabled, boolean verified) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, business_status, reservation_enabled,
                                   open_time, close_time, closed_days, map_x, map_y, real_lat, real_lng, real_verified, address)
                VALUES (?, '목록 가게', 'cafe', 'center', ?, ?, ?, '10:00', '22:00', 'mon', 30.5, 40, 37.5, 127.0, ?, '중앙길 1')
                """, region, joinStatus, businessStatus, reservationEnabled, verified);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private ResultActions location(long storeId, MockHttpSession session, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/stores/{id}/location", storeId).session(session).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new HashMap<>(body))));
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long region(String name) {
        jdbc.update("INSERT INTO region (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
