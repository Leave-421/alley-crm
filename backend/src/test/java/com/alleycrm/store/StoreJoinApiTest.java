package com.alleycrm.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** C5 가게 가입 신청 · 승인 · 거절 · 재신청, 승인 전 사장님 기능 거절 (FR-STO-01, FR-AUTH-04). */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class StoreJoinApiTest {

    static final String PASSWORD = "password1";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AccountService accountService;
    @Autowired
    StoreService storeService;
    @Autowired
    ApplicationEvents events;

    long regionId;
    long otherRegionId;
    String associationLogin;
    String otherAssociationLogin;

    @BeforeEach
    void seed() {
        regionId = region("가입 테스트 권역");
        otherRegionId = region("다른 권역");
        associationLogin = "assoc" + System.nanoTime();
        accountService.createAssociation(regionId, associationLogin, PASSWORD);
        otherAssociationLogin = "assoc" + System.nanoTime();
        accountService.createAssociation(otherRegionId, otherAssociationLogin, PASSWORD);
    }

    private long region(String name) {
        jdbc.update("INSERT INTO region (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
    void FR_STO_01_1_신청하면_승인_대기이고_상인회가_승인하면_사장님_기능을_쓸_수_있다() throws Exception {
        String owner = newLoginId();
        String storeId = apply(owner, Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.joinStatus").value("requested"))
                .andExpect(jsonPath("$.regionId").value(String.valueOf(regionId)))
                .andExpect(jsonPath("$.openTime").value("10:00"))
                .andExpect(jsonPath("$.closedDays[0]").value("mon"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"storeId\":\"(\\d+)\".*", "$1");
        // 영업시간은 벽시계 그대로 저장된다(JVM 시간대만큼 밀리지 않음)
        assertThat(jdbc.queryForMap("SELECT TIME_FORMAT(open_time, '%H:%i') AS o, TIME_FORMAT(close_time, '%H:%i') AS c FROM store WHERE id = ?",
                Long.parseLong(storeId))).containsEntry("o", "10:00").containsEntry("c", "22:00");

        // 승인 전에도 로그인해서 가입 상태를 본다(O-11)
        MockHttpSession ownerSession = login(owner);
        mvc.perform(get("/api/v1/store-joins/mine").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("requested"));
        // FR-AUTH-04 #1: 승인 전 사장님 기능은 거절
        assertForbidden(owner);

        mvc.perform(post("/api/v1/store-joins/{id}/approve", storeId).session(login(associationLogin)).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("approved"));

        assertThatCode(() -> storeService.requireApprovedOwnStore(accountService.authenticate(owner, PASSWORD)))
                .doesNotThrowAnyException();
        assertThat(events.stream(StoreJoinDecidedEvent.class))
                .containsExactly(new StoreJoinDecidedEvent(Long.parseLong(storeId), "approved"));
    }

    @Test
    void FR_STO_01_2_상인회가_거절하면_사장님_기능을_못_쓰고_고쳐서_다시_신청할_수_있다() throws Exception {
        String owner = newLoginId();
        String storeId = storeIdOf(apply(owner, Map.of()));

        mvc.perform(post("/api/v1/store-joins/{id}/reject", storeId).session(login(associationLogin)).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("rejected"));
        assertForbidden(owner);

        MockHttpSession ownerSession = login(owner);
        mvc.perform(post("/api/v1/store-joins/mine/reapply").session(ownerSession).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(info(Map.of("name", "고친 이름")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinStatus").value("requested"))
                .andExpect(jsonPath("$.name").value("고친 이름"));

        // 거절 상태가 아니면 다시 신청할 수 없다
        mvc.perform(post("/api/v1/store-joins/mine/reapply").session(ownerSession).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(info(Map.of()))))
                .andExpect(status().isConflict());
    }

    @Test
    void FR_AUTH_04_2_사장님_계정이_있는_가게에_두_번째_사장님_계정은_거절() throws Exception {
        long storeId = Long.parseLong(storeIdOf(apply(newLoginId(), Map.of())));

        assertThatThrownBy(() -> accountService.createOwner(storeId, newLoginId(), PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE store_id = ?", Integer.class, storeId)).isEqualTo(1);
    }

    @Test
    void 상인회는_자기_권역의_신청만_보고_처리한다() throws Exception {
        String storeId = storeIdOf(apply(newLoginId(), Map.of()));

        mvc.perform(get("/api/v1/store-joins").session(login(associationLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].storeId").value(storeId));
        mvc.perform(get("/api/v1/store-joins").session(login(otherAssociationLogin)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/v1/store-joins/{id}/approve", storeId).session(login(otherAssociationLogin)).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isNotFound());

        MockHttpSession assoc = login(associationLogin);
        mvc.perform(post("/api/v1/store-joins/{id}/approve", storeId).session(assoc).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk());
        // 신청 상태가 아니면 다시 처리할 수 없다
        mvc.perform(post("/api/v1/store-joins/{id}/reject", storeId).session(assoc).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/store-joins").param("status", "approved").session(assoc))
                .andExpect(jsonPath("$.items[0].joinStatus").value("approved"));
    }

    @Test
    void 사장님_손님은_가입_목록과_승인을_쓸_수_없다() throws Exception {
        String owner = newLoginId();
        String storeId = storeIdOf(apply(owner, Map.of()));
        MockHttpSession ownerSession = login(owner);

        mvc.perform(get("/api/v1/store-joins").session(ownerSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(post("/api/v1/store-joins/{id}/approve", storeId).session(ownerSession).with(TestCsrf.csrf(mvc)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/store-joins/mine").session(login(associationLogin)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/store-joins/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void 잘못된_신청은_400_없는_권역은_404_겹치는_아이디는_409이고_가게도_남지_않는다() throws Exception {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("name", ""),
                Map.of("category", "karaoke"),
                Map.of("alley", "nowhere"),
                Map.of("openTime", "22:00", "closeTime", "10:00"),
                Map.of("openTime", "25:00"),
                Map.of("closedDays", List.of("mon", "mon")),
                Map.of("closedDays", List.of("holiday")))) {
            apply(newLoginId(), bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid"));
        }
        apply(newLoginId(), Map.of("regionId", "999999999")).andExpect(status().isNotFound());

        String owner = newLoginId();
        apply(owner, Map.of()).andExpect(status().isCreated());
        apply(owner, Map.of()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store WHERE region_id = ?", Integer.class, regionId)).isEqualTo(1);
    }

    @Test
    void 경로의_가게가_내_가게가_아니면_404() throws Exception {
        String owner = newLoginId();
        long storeId = Long.parseLong(storeIdOf(apply(owner, Map.of())));
        var me = accountService.authenticate(owner, PASSWORD);

        assertThatThrownBy(() -> storeService.requireApprovedOwnStore(me, storeId + 1000))
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private void assertForbidden(String ownerLogin) {
        assertThatThrownBy(() -> storeService.requireApprovedOwnStore(accountService.authenticate(ownerLogin, PASSWORD)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
    }

    private ResultActions apply(String loginId, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = info(Map.of());
        body.put("loginId", loginId);
        body.put("password", PASSWORD);
        body.put("regionId", String.valueOf(regionId));
        body.putAll(overrides);
        return mvc.perform(post("/api/v1/store-joins").with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> info(Map<String, Object> overrides) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "골목 카페");
        b.put("category", "cafe");
        b.put("alley", "center");
        b.put("openTime", "10:00");
        b.put("closeTime", "22:00");
        b.put("closedDays", List.of("mon"));
        b.putAll(overrides);
        return b;
    }

    private static String storeIdOf(ResultActions created) throws Exception {
        return created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"storeId\":\"(\\d+)\".*", "$1");
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static String newLoginId() {
        return "s" + System.nanoTime();
    }
}
