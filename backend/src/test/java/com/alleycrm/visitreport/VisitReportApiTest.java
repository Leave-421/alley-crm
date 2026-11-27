package com.alleycrm.visitreport;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.ZoneOffset;
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

/** C23 방문 신고: 회원 신고 → 상인회 확인 요청 → 사장님 답변 → 상인회 결과 기록 (FR-STL-08). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VisitReportApiTest {

    static final String PASSWORD = "password1";
    /** 2026-12-02 10:00 KST */
    static final Instant NOW = Instant.parse("2026-12-02T01:00:00Z");
    /** 2026-11-20 19:00 KST — 11월 예약 */
    static final Instant NOVEMBER_VISIT = Instant.parse("2026-11-20T10:00:00Z");
    static final String MEMBER_NAME = "신고회원이름";

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
    @Autowired
    VisitReportService service;

    long regionId;
    long otherRegionId;
    long storeId;
    long otherStoreId;
    long customerId;
    long ownerAccountId;
    String memberLogin;
    String otherMemberLogin;
    String ownerLogin;
    String otherOwnerLogin;
    String associationLogin;
    String otherAssociationLogin;
    long visited;
    long autoCanceled;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        regionId = region("신고 권역");
        otherRegionId = region("신고 다른 권역");
        storeId = store(regionId, "신고 가게");
        otherStoreId = store(regionId, "신고 다른 가게");
        String suffix = String.valueOf(System.nanoTime());
        memberLogin = "vrmember" + suffix;
        long memberAccount = accountService.signupCustomer(memberLogin, PASSWORD, MEMBER_NAME, null, null).id();
        customerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, memberAccount);
        otherMemberLogin = "vrother" + suffix;
        accountService.signupCustomer(otherMemberLogin, PASSWORD, "다른 회원", null, null);
        ownerLogin = "vrowner" + suffix;
        ownerAccountId = accountService.createOwner(storeId, ownerLogin, PASSWORD).id();
        otherOwnerLogin = "vrowner2" + suffix;
        accountService.createOwner(otherStoreId, otherOwnerLogin, PASSWORD);
        associationLogin = "vrassoc" + suffix;
        accountService.createAssociation(regionId, associationLogin, PASSWORD);
        otherAssociationLogin = "vrassoc2" + suffix;
        accountService.createAssociation(otherRegionId, otherAssociationLogin, PASSWORD);

        visited = reservation("visited", null, 30000);
        autoCanceled = reservation("canceled", "auto_canceled", null);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM notification WHERE type = 'visit_report_check_requested'");
        jdbc.update("DELETE FROM visit_report WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id IN (?, ?))",
                storeId, otherStoreId);
        jdbc.update("DELETE FROM reservation WHERE store_id IN (?, ?)", storeId, otherStoreId);
        jdbc.update("DELETE FROM settlement WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM customer WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'vr%')");
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'vr%'");
        jdbc.update("DELETE FROM store WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM region WHERE id IN (?, ?)", regionId, otherRegionId);
    }

    @Test
    void FR_STL_08_1_방문_처리된_예약에_금액_신고하면_접수되고_상인회_목록에_나온다() throws Exception {
        report(login(memberLogin), visited, Map.of("type", "amount", "claimedAmount", 25000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("amount"))
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.claimedAmount").value(25000))
                .andExpect(jsonPath("$.reservationId").value(String.valueOf(visited)));

        mvc.perform(get("/api/v1/association/visit-reports").session(login(associationLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("received"))
                .andExpect(jsonPath("$.items[0].month").value("2026-11"));
        // 처리되지 않은 신고가 있으면 그 달 정산을 확정할 수 없다(FR-STL-05)
        mvc.perform(post("/api/v1/association/settlements/2026-11/confirm").session(login(associationLogin))
                .with(TestCsrf.csrf(mvc))).andExpect(status().isConflict());
    }

    @Test
    void FR_STL_08_2_그_달_정산이_확정되면_금액_미처리_신고_모두_거절() throws Exception {
        jdbc.update("INSERT INTO settlement (region_id, month, status, confirmed_at) VALUES (?, '2026-11', 'confirmed', ?)",
                regionId, utc(NOW));
        MockHttpSession member = login(memberLogin);

        report(member, visited, Map.of("type", "amount")).andExpect(status().isConflict());
        report(member, autoCanceled, Map.of("type", "not_processed")).andExpect(status().isConflict());
        assertThat(count()).isZero();
    }

    @Test
    void FR_STL_08_3_방문했어요는_자동_취소된_예약만() throws Exception {
        long confirmed = reservation("confirmed", null, null);
        long customerCanceled = reservation("canceled", "customer_canceled", null);
        MockHttpSession member = login(memberLogin);

        for (long id : new long[] {confirmed, visited, customerCanceled}) {
            report(member, id, Map.of("type", "not_processed")).andExpect(status().isConflict());
        }
        report(member, autoCanceled, Map.of("type", "amount")).andExpect(status().isConflict());
        report(member, autoCanceled, Map.of("type", "not_processed"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimedAmount").doesNotExist());
    }

    @Test
    void FR_STL_08_4_확인_요청_뒤_아님을_기록하면_결과만_남고_예약은_그대로() throws Exception {
        long reportId = reportId(report(login(memberLogin), visited, Map.of("type", "amount", "claimedAmount", 25000)));
        MockHttpSession association = login(associationLogin);

        // 확인 요청 전에는 결과를 기록할 수 없다
        result(association, reportId, "invalid").andExpect(status().isConflict());
        // 사장님은 확인 요청 전 신고를 보지 못한다
        mvc.perform(get("/api/v1/owner/visit-reports").session(login(ownerLogin))).andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(post("/api/v1/association/visit-reports/{id}/owner-check", reportId).session(association)
                        .with(TestCsrf.csrf(mvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("owner_check_requested"));
        String payload = jdbc.queryForObject("""
                SELECT payload FROM notification WHERE recipient_type = 'account' AND recipient_id = ?
                  AND type = 'visit_report_check_requested'""", String.class, ownerAccountId);
        assertThat(json.readValue(payload, Map.class))
                .isEqualTo(Map.of("visitReportId", String.valueOf(reportId), "reservationId", String.valueOf(visited)));

        MockHttpSession owner = login(ownerLogin);
        mvc.perform(get("/api/v1/owner/visit-reports").session(owner))
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(String.valueOf(reportId))));
        reply(owner, reportId, "  영수증 확인 결과 30,000원이 맞습니다.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerReply").value("영수증 확인 결과 30,000원이 맞습니다."))
                .andExpect(jsonPath("$.ownerRepliedAt").isNotEmpty());

        result(association, reportId, "invalid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.result").value("invalid"))
                .andExpect(jsonPath("$.handledAt").value(Matchers.startsWith("2026-12-02T01:00")));
        result(association, reportId, "valid").andExpect(status().isConflict()); // 한 번 기록하면 끝
        reply(owner, reportId, "다시").andExpect(status().isConflict());

        // 금액 · 예약은 그대로, '맞음'이 아니므로 정정 · 늦은 방문 처리 대상이 아니다
        assertThat(jdbc.queryForMap("SELECT status, visit_amount FROM reservation WHERE id = ?", visited))
                .containsEntry("status", "visited").containsEntry("visit_amount", 30000);
        assertThat(service.hasValidReport(visited, VisitReport.TYPE_AMOUNT)).isFalse();
        mvc.perform(get("/api/v1/visit-reports/mine").session(login(memberLogin)))
                .andExpect(jsonPath("$.items[0].result").value("invalid"))
                .andExpect(jsonPath("$.items[0].ownerReply").doesNotExist());
    }

    @Test
    void 맞음으로_기록하면_D22_D23이_확인할_수_있다() throws Exception {
        long reportId = reportId(report(login(memberLogin), autoCanceled, Map.of("type", "not_processed")));
        MockHttpSession association = login(associationLogin);
        mvc.perform(post("/api/v1/association/visit-reports/{id}/owner-check", reportId).session(association)
                .with(TestCsrf.csrf(mvc))).andExpect(status().isOk());

        result(association, reportId, "valid").andExpect(status().isOk());

        assertThat(service.hasValidReport(autoCanceled, VisitReport.TYPE_NOT_PROCESSED)).isTrue();
        assertThat(service.hasValidReport(autoCanceled, VisitReport.TYPE_AMOUNT)).isFalse();
    }

    @Test
    void FR_STL_08_5_상인회는_예약_정보만_보고_손님_이름_연락처는_없다() throws Exception {
        long reportId = reportId(report(login(memberLogin), visited, Map.of("type", "amount", "claimedAmount", 25000)));

        String body = mvc.perform(get("/api/v1/association/visit-reports").session(login(associationLogin)))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(reportId)))
                .andExpect(jsonPath("$.items[0].storeName").value("신고 가게"))
                .andExpect(jsonPath("$.items[0].reservationNo").isNotEmpty())
                .andExpect(jsonPath("$.items[0].startAt").value(Matchers.startsWith("2026-11-20T10:00:00")))
                .andExpect(jsonPath("$.items[0].visitAmount").value(30000))
                .andExpect(jsonPath("$.items[0].claimedAmount").value(25000))
                .andExpect(jsonPath("$.items[0].ownerReply").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("items").get(0).propertyNames())
                .containsExactlyInAnyOrder("id", "type", "status", "result", "storeId", "storeName", "reservationId",
                        "reservationNo", "startAt", "month", "visitAmount", "claimedAmount", "ownerReply", "ownerRepliedAt",
                        "createdAt", "handledAt");
        assertThat(body).doesNotContain(MEMBER_NAME);
    }

    @Test
    void 남의_예약_다른_권역_다른_가게는_404_같은_종류_다시_신고는_409() throws Exception {
        report(login(otherMemberLogin), visited, Map.of("type", "amount")).andExpect(status().isNotFound());
        long reportId = reportId(report(login(memberLogin), visited, Map.of("type", "amount")));
        report(login(memberLogin), visited, Map.of("type", "amount")).andExpect(status().isConflict());

        MockHttpSession otherAssociation = login(otherAssociationLogin);
        mvc.perform(get("/api/v1/association/visit-reports").session(otherAssociation))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/v1/association/visit-reports/{id}/owner-check", reportId).session(otherAssociation)
                .with(TestCsrf.csrf(mvc))).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/association/visit-reports/{id}/owner-check", reportId).session(login(associationLogin))
                .with(TestCsrf.csrf(mvc))).andExpect(status().isOk());
        reply(login(otherOwnerLogin), reportId, "남의 신고").andExpect(status().isNotFound());
    }

    @Test
    void 역할_로그인_입력_검사() throws Exception {
        mvc.perform(post("/api/v1/reservations/{id}/visit-reports", visited).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"amount\"}"))
                .andExpect(status().isUnauthorized());
        report(login(ownerLogin), visited, Map.of("type", "amount")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/association/visit-reports").session(login(memberLogin))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/owner/visit-reports").session(login(associationLogin))).andExpect(status().isForbidden());

        MockHttpSession member = login(memberLogin);
        report(member, visited, Map.of("type", "wrong")).andExpect(status().isBadRequest());
        report(member, visited, Map.of("type", "amount", "claimedAmount", -1)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/association/visit-reports").param("status", "wrong").session(login(associationLogin)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reservations/{id}/visit-reports", visited).session(member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"amount\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
        assertThat(count()).isZero();
    }

    private long region(String name) {
        jdbc.update("INSERT INTO region (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long store(long region, String name) {
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, closed_days)
                VALUES (?, ?, 'cafe', 'center', 'approved', '10:00', '22:00', '')
                """, region, name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long reservation(String status, String cancelReason, Integer visitAmount) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visit_amount)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, "V" + System.nanoTime() % 1_000_000_000L, storeId, customerId, utc(NOVEMBER_VISIT), status, cancelReason,
                utc(NOVEMBER_VISIT.minusSeconds(86_400)), visitAmount);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit_report WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)",
                Integer.class, storeId);
    }

    private ResultActions report(MockHttpSession session, long reservationId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/reservations/{id}/visit-reports", reservationId).session(session)
                .with(TestCsrf.csrf(mvc)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions result(MockHttpSession session, long reportId, String result) throws Exception {
        return mvc.perform(post("/api/v1/association/visit-reports/{id}/result", reportId).session(session)
                .with(TestCsrf.csrf(mvc)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"" + result + "\"}"));
    }

    private ResultActions reply(MockHttpSession session, long reportId, String reply) throws Exception {
        return mvc.perform(post("/api/v1/owner/visit-reports/{id}/reply", reportId).session(session)
                .with(TestCsrf.csrf(mvc)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reply", reply))));
    }

    private long reportId(ResultActions created) throws Exception {
        return Long.parseLong(json.readTree(created.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("id").asString());
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
