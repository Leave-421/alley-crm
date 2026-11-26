package com.alleycrm.recommend.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.ReservationStateService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 소개 판정 끝까지 확인(E15 #80, FR-STL-01, 05-policy 「소개 판정」). 손님은 실제 API로 기준 예약 → 정식 코스 제안(3.1) →
 * '다음 코스로 예약하기'(D12)를 하고, 사장님 방문 처리(D11)가 소개 확정 · 수수료(D15)로 이어지는지 본다.
 * 판정은 D12 단계 40(마감 · 소개 후보)과 D15(예약 건당 한 번 확정)가 하고, E15는 새 저장을 두지 않는다.
 * FR-STL-01 #7(기준 예약일 변경)은 예약 변경 기능이 없어 여기서 확인하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReferralJudgementTest {

    private static final String PASSWORD = "password1";
    /** 2026-11-25 10:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-11-25T01:00:00Z");
    /** 기준 예약: 2026-11-25 18:00 Asia/Seoul */
    private static final String BASE_START = "2026-11-25T09:00:00Z";
    /** 2026-11-25 23:59 Asia/Seoul — 마감 직전 */
    private static final Instant JUST_BEFORE_DEADLINE = Instant.parse("2026-11-25T14:59:00Z");
    /** 2026-11-26 00:00 Asia/Seoul — 마감 */
    private static final Instant DEADLINE = Instant.parse("2026-11-25T15:00:00Z");
    /** B 방문일: 2026-11-28 12:00 Asia/Seoul (기준 예약일보다 뒤) */
    private static final String B_START = "2026-11-28T03:00:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AppClock clock;

    @Autowired
    AccountService accounts;

    @Autowired
    ReservationStateService reservationStates;

    long region;
    long storeA;
    long storeB;
    long customer;
    MockHttpSession member;
    long base;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('소개 판정 권역')");
        storeA = store("A 카페", "cafe");
        storeB = store("B 디저트", "dessert");
        String loginId = "s" + System.nanoTime();
        long accountId = accounts.signupCustomer(loginId, PASSWORD, "회원", null, null).id();
        customer = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        member = login(loginId);
        base = id(request(storeA, BASE_START, null, null, null).andExpect(status().isCreated()));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void 마감_전에_다음_코스로_신청하면_소개_후보_제안_ID_기준_예약이_연결된다_FR_STL_01_1() throws Exception {
        long proposal = proposeB();
        clock.setTo(JUST_BEFORE_DEADLINE);

        long b = id(request(storeB, B_START, base, proposal, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromRecommendation").value(true))
                .andExpect(jsonPath("$.senderStoreId").doesNotExist()));

        Map<String, Object> row = reservation(b);
        assertThat(row.get("course_proposal_id")).isEqualTo(proposal);
        assertThat(row.get("course_root_id")).isEqualTo(base);
        assertThat(row.get("sender_store_id")).isEqualTo(storeA);
    }

    @Test
    void 기준_예약일_당일이_지나_신청하면_소개가_아니다_FR_STL_01_2() throws Exception {
        long proposal = proposeB();
        clock.setTo(DEADLINE);

        long b = id(request(storeB, B_START, base, proposal, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromRecommendation").value(false)));

        Map<String, Object> row = reservation(b);
        assertThat(row.get("course_proposal_id")).isNull();
        assertThat(row.get("sender_store_id")).isNull();
        visit(b);
        assertThat(feeCount(b)).isZero();
    }

    @Test
    void 쿠폰을_적용한_일반_예약은_쿠폰만_적용되고_소개가_아니다_FR_STL_01_3() throws Exception {
        proposeB(); // B를 정식 제안으로도 봤지만 '다음 코스로 예약하기'를 쓰지 않음
        long event = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + storeB + ", 'fixed_discount', 3000, 7, 10, 1, '2026-11-24 01:00:00')");
        LocalDateTime downloadedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(1);
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'downloaded')
                """, customer, event, storeB, downloadedAt, downloadedAt.plusDays(7));
        long coupon = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        long b = id(request(storeB, B_START, null, null, coupon).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coupon.couponId").value(String.valueOf(coupon)))
                .andExpect(jsonPath("$.fromRecommendation").value(false)));
        visit(b);

        assertThat(jdbc.queryForObject("SELECT status FROM coupon WHERE id = ?", String.class, coupon)).isEqualTo("applied");
        assertThat(reservation(b).get("sender_store_id")).isNull();
        assertThat(feeCount(b)).isZero();
    }

    @Test
    void 기한_안에_신청하면_B_방문일이_기준_예약일보다_뒤여도_방문_처리로_확정_FR_STL_01_4() throws Exception {
        long b = courseReservationBeforeDeadline();

        visit(b);

        Map<String, Object> fee = jdbc.queryForMap("SELECT * FROM referral_fee WHERE reservation_id = ?", b);
        assertThat(fee.get("receiving_store_id")).isEqualTo(storeB);
        assertThat(fee.get("sender_store_id")).isEqualTo(storeA);
    }

    @Test
    void A_예약이_취소돼도_B가_방문하면_확정_B가_수수료를_내고_A_보낸_실적이_는다_FR_STL_01_5() throws Exception {
        long b = courseReservationBeforeDeadline();
        reservationStates.reject(storeA, base);

        visit(b);

        assertThat(feeCount(b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM referral_fee WHERE reservation_id = ?", Integer.class, b))
                .isPositive();
        mvc.perform(get("/api/v1/owner/referral-stats").param("month", "2026-11").session(owner(storeA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentCount").value(1))
                .andExpect(jsonPath("$.receivedCount").value(0));
        mvc.perform(get("/api/v1/owner/referral-stats").param("month", "2026-11").session(owner(storeB)))
                .andExpect(jsonPath("$.sentCount").value(0))
                .andExpect(jsonPath("$.receivedCount").value(1));
    }

    @Test
    void B_예약이_취소되면_소개가_아니다_FR_STL_01_6() throws Exception {
        long b = courseRequestBeforeDeadline();

        reservationStates.reject(storeB, b); // 신청 상태에서 가게 거절 → 취소

        assertThatThrownBy(() -> reservationStates.visit(storeB, b, 30000)).isInstanceOf(ApiException.class);
        assertThat(feeCount(b)).isZero();
    }

    @Test
    void 방문_처리를_두_번_시도해도_소개_수수료는_한_번_FR_STL_01_8() throws Exception {
        long b = courseReservationBeforeDeadline();
        visit(b);

        assertThatThrownBy(() -> reservationStates.visit(storeB, b, 30000)).isInstanceOf(ApiException.class);

        assertThat(feeCount(b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_tx WHERE reservation_id = ?", Integer.class, b))
                .isEqualTo(2); // 가게 → 기금 한 번
    }

    // ---- 준비 ----

    /** 기준 예약의 정식 코스 제안을 조회하고 B를 그렸다고 기록(3.1). B의 제안 ID. */
    private long proposeB() throws Exception {
        mvc.perform(get("/api/v1/course-proposals").param("baseReservationId", String.valueOf(base)).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].store.id").value(org.hamcrest.Matchers.hasItem(String.valueOf(storeB))));
        String body = mvc.perform(post("/api/v1/course-proposals").session(member).with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseReservationId\":\"" + base + "\",\"storeIds\":[\"" + storeB + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.readTree(body).get("items").get(0).get("courseProposalId").asString());
    }

    /** FR-STL-01 #1처럼 마감 직전에 '다음 코스로 예약하기'로 신청한(신청 상태) 예약. */
    private long courseRequestBeforeDeadline() throws Exception {
        long proposal = proposeB();
        clock.setTo(JUST_BEFORE_DEADLINE);
        return id(request(storeB, B_START, base, proposal, null).andExpect(status().isCreated()));
    }

    /** 위 예약을 B 사장님이 확정함. */
    private long courseReservationBeforeDeadline() throws Exception {
        long b = courseRequestBeforeDeadline();
        reservationStates.confirm(storeB, b);
        return b;
    }

    /** B 사장님 방문 처리(D11). 방문 처리 시각 제한은 없다. */
    private void visit(long reservationB) {
        if ("requested".equals(reservation(reservationB).get("status"))) {
            reservationStates.confirm(storeB, reservationB);
        }
        reservationStates.visit(storeB, reservationB, 30000);
    }

    private ResultActions request(long store, String startAt, Long rootId, Long proposalId, Long couponId)
            throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("storeId", String.valueOf(store));
        b.put("startAt", startAt);
        b.put("partySize", 2);
        b.put("shareConsent", true);
        b.put("courseRootId", rootId == null ? null : String.valueOf(rootId));
        b.put("courseProposalId", proposalId == null ? null : String.valueOf(proposalId));
        b.put("couponId", couponId == null ? null : String.valueOf(couponId));
        return mvc.perform(post("/api/v1/reservations").session(member).with(TestCsrf.csrf(mvc))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private Map<String, Object> reservation(long id) {
        return jdbc.queryForMap("SELECT status, course_proposal_id, course_root_id, sender_store_id FROM reservation WHERE id = ?", id);
    }

    private int feeCount(long reservation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM referral_fee WHERE reservation_id = ?", Integer.class, reservation);
    }

    private long id(ResultActions result) throws Exception {
        return Long.parseLong(json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString());
    }

    private long store(String name, String category) {
        return insert("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (%d, '%s', '%s', 'center', 'approved', '10:00', '22:00', 5, 7)
                """.formatted(region, name, category));
    }

    private MockHttpSession owner(long store) throws Exception {
        String loginId = "o" + System.nanoTime();
        accounts.createOwner(store, loginId, PASSWORD);
        return login(loginId);
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
