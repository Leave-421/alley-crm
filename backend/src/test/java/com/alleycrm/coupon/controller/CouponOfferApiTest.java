package com.alleycrm.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 상세 · 다운로드 API(CONTRACTS_API 3.4, FR-CPN-03 · 04 · 10). 계정은 DB에 넣고 실제 로그인 흐름으로 세션을 받는다.
 * 쿠폰 상세를 로그인 없이 여는 보안 설정은 C(3.4 K-1) 뒤라, 여기서는 로그인한 요청으로 확인한다.
 * 동시 요청 검증은 E9(#106).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponOfferApiTest {

    private static final String PASSWORD = "password1";
    private static final Duration CLOCK_DRIFT = Duration.ofSeconds(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Clock clock;

    long region;
    long storeA;
    long storeB;
    long storeC;
    long eventB;
    long customerId;
    MockHttpSession member;
    MockHttpSession otherMember;
    MockHttpSession owner;
    MockHttpSession association;
    Cookie browser;
    long visitorId;

    @BeforeEach
    void seed() throws Exception {
        region = insert("INSERT INTO region (name) VALUES ('쿠폰 발급 권역')");
        storeA = store("A", "approved");
        storeB = store("B", "approved");
        storeC = store("C", "approved");
        eventB = fixedEvent(storeB, 10, 0, 7);

        String memberLogin = account("customer", null, null);
        customerId = memberCustomer(memberLogin);
        member = login(memberLogin);
        String otherLogin = account("customer", null, null);
        memberCustomer(otherLogin);
        otherMember = login(otherLogin);
        owner = login(account("owner", storeB, null));
        association = login(account("association", null, region));

        UUID anonId = UUID.randomUUID();
        visitorId = insert("INSERT INTO visitor (anon_id) VALUES ('" + anonId + "')");
        browser = new Cookie("alley_vid", anonId.toString());
    }

    // ---- 쿠폰 상세 ----

    @Test
    void 쿠폰_상세는_혜택과_받을_수_있는지를_준다() throws Exception {
        long percent = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_rate, max_discount_amount, "
                + "valid_days, conditions, service_value, total_quantity, issued_count, confirmed_at) "
                + "VALUES (" + storeC + ", 'percent_discount', 10, 5000, 3, '2인 이상', 1234, 50, 0, UTC_TIMESTAMP(6))");

        mvc.perform(get("/api/v1/coupon-offers/" + storeC).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(String.valueOf(storeC)))
                .andExpect(jsonPath("$.event.eventId").value(String.valueOf(percent)))
                .andExpect(jsonPath("$.event.benefitType").value("percent_discount"))
                .andExpect(jsonPath("$.event.discountAmount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.event.discountPercent").value(10))
                .andExpect(jsonPath("$.event.maxDiscountAmount").value(5000))
                .andExpect(jsonPath("$.event.validDays").value(3))
                .andExpect(jsonPath("$.event.conditions").value("2인 이상"))
                .andExpect(jsonPath("$.event.serviceValue").doesNotExist())
                .andExpect(jsonPath("$.event.remainingQuantity").doesNotExist())
                .andExpect(jsonPath("$.event.totalQuantity").doesNotExist())
                .andExpect(jsonPath("$.downloadStatus").value("available"))
                .andExpect(jsonPath("$.nextDownloadableAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.myCoupons").isEmpty());
    }

    @Test
    void 행사가_없거나_다_나갔거나_가게가_휴업이면_unavailable() throws Exception {
        mvc.perform(get("/api/v1/coupon-offers/" + storeA).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.downloadStatus").value("unavailable"));

        fixedEvent(storeC, 1, 1, 7);
        mvc.perform(get("/api/v1/coupon-offers/" + storeC).session(member))
                .andExpect(jsonPath("$.downloadStatus").value("unavailable"));

        jdbc.update("UPDATE store SET business_status = 'paused' WHERE id = ?", storeB);
        mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(member))
                .andExpect(jsonPath("$.event.eventId").value(String.valueOf(eventB)))
                .andExpect(jsonPath("$.downloadStatus").value("unavailable"));
    }

    @Test
    void 쿠폰_상세는_사장님과_상인회에게_403() throws Exception {
        for (MockHttpSession session : new MockHttpSession[] {owner, association}) {
            mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        }
    }

    @Test
    void 보이지_않는_가게나_없는_가게의_쿠폰_상세는_404() throws Exception {
        long requested = store("신청 중", "requested");
        fixedEvent(requested, 10, 0, 7);

        for (String id : new String[] {String.valueOf(requested), "999999999", "abc"}) {
            mvc.perform(get("/api/v1/coupon-offers/" + id).session(member))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    // ---- 다운로드 ----

    @Test
    void 다운로드하면_201이고_출처와_만료_시각을_저장한다_FR_CPN_10_1() throws Exception {
        long exposure = exposure(visitorId, storeA, storeB);
        LocalDateTime before = now();

        mvc.perform(download(exposure).session(member))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponId").isString())
                .andExpect(jsonPath("$.eventId").value(String.valueOf(eventB)))
                .andExpect(jsonPath("$.storeId").value(String.valueOf(storeB)))
                .andExpect(jsonPath("$.status").value("downloaded"))
                .andExpect(jsonPath("$.benefitType").value("fixed_discount"))
                .andExpect(jsonPath("$.discountAmount").value(3000))
                .andExpect(jsonPath("$.discountPercent").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.downloadedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.expiresAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.sourceBaseStoreId").doesNotExist());

        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM coupon WHERE customer_id = ?", customerId);
        assertThat(saved.get("coupon_policy_id")).isEqualTo(eventB);
        assertThat(saved.get("target_store_id")).isEqualTo(storeB);
        assertThat(saved.get("source_base_store_id")).isEqualTo(storeA);
        assertThat(saved.get("source_exposure_id")).isEqualTo(exposure);
        assertThat(saved.get("status")).isEqualTo("downloaded");
        LocalDateTime downloadedAt = jdbc.queryForObject(
                "SELECT downloaded_at FROM coupon WHERE customer_id = ?", LocalDateTime.class, customerId);
        LocalDateTime expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM coupon WHERE customer_id = ?", LocalDateTime.class, customerId);
        assertThat(downloadedAt).isBetween(before.minus(CLOCK_DRIFT), now().plus(CLOCK_DRIFT));
        assertThat(expiresAt).isEqualTo(downloadedAt.plusHours(7 * 24));
        assertThat(issuedCount(eventB)).isEqualTo(1);
    }

    @Test
    void 받은_뒤_다시_받으면_409이고_상세는_30일_대기와_다음_가능일을_준다_FR_CPN_04_1() throws Exception {
        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member)).andExpect(status().isCreated());
        LocalDateTime downloadedAt = jdbc.queryForObject(
                "SELECT downloaded_at FROM coupon WHERE customer_id = ?", LocalDateTime.class, customerId);

        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        // 거절 때 발급 수가 되돌아가는지는 테스트 트랜잭션 없이 CouponDownloadCommitTest가 본다
        assertThat(couponCount()).isEqualTo(1);

        mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(member))
                .andExpect(jsonPath("$.downloadStatus").value("waiting"))
                .andExpect(jsonPath("$.nextDownloadableAt")
                        .value(downloadedAt.plusDays(30).toInstant(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.myCoupons.length()").value(1))
                .andExpect(jsonPath("$.myCoupons[0].eventId").value(String.valueOf(eventB)))
                .andExpect(jsonPath("$.myCoupons[0].status").value("downloaded"));
        mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(otherMember))
                .andExpect(jsonPath("$.downloadStatus").value("available"))
                .andExpect(jsonPath("$.myCoupons").isEmpty());
    }

    @Test
    void 다른_기준_가게에서_추천받아도_같은_행사면_제한을_공유하고_출처는_그대로_FR_CPN_04_2_FR_CPN_10_2() throws Exception {
        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member)).andExpect(status().isCreated());

        mvc.perform(download(exposure(visitorId, storeC, storeB)).session(member))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT source_base_store_id FROM coupon WHERE customer_id = ?",
                Long.class, customerId)).isEqualTo(storeA);
    }

    @Test
    void 받은_지_31일이어도_미사용_적용_쿠폰이_있으면_409_FR_CPN_04_3() throws Exception {
        pastCoupon(eventB, storeB, now().minusDays(31), "applied");

        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member))
                .andExpect(status().isConflict());
        assertThat(couponCount()).isEqualTo(1);
        mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(member))
                .andExpect(jsonPath("$.downloadStatus").value("applied_unused"))
                .andExpect(jsonPath("$.myCoupons[0].status").value("applied"));
    }

    @Test
    void 받은_지_31일이고_만료됐으면_다시_받는다_FR_CPN_04_4() throws Exception {
        pastCoupon(eventB, storeB, now().minusDays(31), "expired");

        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member))
                .andExpect(status().isCreated());
    }

    @Test
    void 휴업_뒤_영업을_재개하면_제한이_풀린다_FR_CPN_04_5() throws Exception {
        pastCoupon(eventB, storeB, now().minusDays(10), "downloaded");
        jdbc.update("UPDATE store SET business_status = 'paused', business_status_changed_at = ? WHERE id = ?",
                now().minusDays(3), storeB);
        long exposure = exposure(visitorId, storeA, storeB);

        mvc.perform(download(exposure).session(member)).andExpect(status().isConflict());

        jdbc.update("UPDATE store SET business_status = 'open', business_status_changed_at = ? WHERE id = ?",
                now().minusDays(1), storeB);
        mvc.perform(download(exposure).session(member)).andExpect(status().isCreated());
    }

    @Test
    void 이전_행사_쿠폰은_새_행사_다운로드를_막지_않고_두_장을_가진다_FR_CPN_04_6_8() throws Exception {
        // 이전 행사 쿠폰: 30일 안에 받았고 아직 기한 안(쿠폰 기간 7일)
        pastCoupon(eventB, storeB, now().minusDays(3), "downloaded");
        jdbc.update("UPDATE coupon_policy SET stopped_at = UTC_TIMESTAMP(6) WHERE id = ?", eventB);
        long newEvent = fixedEvent(storeB, 10, 0, 7);

        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(String.valueOf(newEvent)));
        mvc.perform(get("/api/v1/coupon-offers/" + storeB).session(member))
                .andExpect(jsonPath("$.myCoupons.length()").value(2));
    }

    @Test
    void 같은_행사_쿠폰이_만료돼도_30일_안이면_409_FR_CPN_04_7() throws Exception {
        pastCoupon(eventB, storeB, now().minusDays(10), "expired");

        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member))
                .andExpect(status().isConflict());
    }

    @Test
    void 행사가_없거나_중단됐거나_다_나갔거나_가게가_영업_중이_아니면_409() throws Exception {
        long exposureToA = exposure(visitorId, storeB, storeA);
        mvc.perform(download(exposureToA).session(member)).andExpect(status().isConflict());

        long soldOut = fixedEvent(storeC, 1, 1, 7);
        mvc.perform(download(exposure(visitorId, storeA, storeC)).session(member)).andExpect(status().isConflict());
        assertThat(issuedCount(soldOut)).isEqualTo(1);

        jdbc.update("UPDATE store SET business_status = 'closed' WHERE id = ?", storeB);
        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member)).andExpect(status().isConflict());
        jdbc.update("UPDATE store SET business_status = 'open' WHERE id = ?", storeB);

        jdbc.update("UPDATE coupon_policy SET stopped_at = UTC_TIMESTAMP(6) WHERE id = ?", eventB);
        mvc.perform(download(exposure(visitorId, storeA, storeB)).session(member)).andExpect(status().isConflict());
        assertThat(couponCount()).isZero();
    }

    @Test
    void 이_회원에_이어진_방문자의_노출은_다른_브라우저에서도_받는다() throws Exception {
        long linked = insert("INSERT INTO visitor (anon_id, customer_id, linked_at) VALUES ('"
                + UUID.randomUUID() + "', " + customerId + ", UTC_TIMESTAMP(6))");

        mvc.perform(post("/api/v1/coupons").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposureId\":\"" + exposure(linked, storeA, storeB) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 다른_사람의_노출이나_없는_노출이면_404이고_발급_수가_그대로() throws Exception {
        long strangerVisitor = insert("INSERT INTO visitor (anon_id) VALUES ('" + UUID.randomUUID() + "')");
        long strangers = exposure(strangerVisitor, storeA, storeB);

        for (long exposure : new long[] {strangers, 999999999L}) {
            mvc.perform(download(exposure).session(member))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
        assertThat(issuedCount(eventB)).isZero();
    }

    @Test
    void 대상_가게가_손님에게_보이지_않으면_404() throws Exception {
        long exposure = exposure(visitorId, storeA, storeB);
        jdbc.update("UPDATE store SET join_status = 'withdrawn' WHERE id = ?", storeB);

        mvc.perform(download(exposure).session(member)).andExpect(status().isNotFound());
    }

    @Test
    void 가게_클릭만으로는_쿠폰이_발급되지_않는다_FR_CPN_03_2() throws Exception {
        mvc.perform(post("/api/v1/store-clicks").session(member).with(csrf(browser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\":\"" + storeB + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(couponCount()).isZero();
        assertThat(issuedCount(eventB)).isZero();
    }

    @Test
    void 로그인하지_않으면_401_FR_CPN_03_1() throws Exception {
        mvc.perform(download(exposure(visitorId, storeA, storeB)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void 사장님과_상인회는_다운로드할_수_없다_403() throws Exception {
        long exposure = exposure(visitorId, storeA, storeB);
        for (MockHttpSession session : new MockHttpSession[] {owner, association}) {
            mvc.perform(download(exposure).session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        }
    }

    @Test
    void exposureId가_없거나_숫자가_아니면_400_invalid() throws Exception {
        for (String body : new String[] {"{}", "{\"exposureId\":\"abc\"}", "{\"exposureId\":\"0\"}", ""}) {
            mvc.perform(post("/api/v1/coupons").session(member).with(csrf(browser))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void CSRF_토큰이_없으면_403_csrf_invalid() throws Exception {
        mvc.perform(post("/api/v1/coupons").session(member).cookie(browser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposureId\":\"" + exposure(visitorId, storeA, storeB) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    // ---- 준비 ----

    /** 이 브라우저(방문자 쿠키)에서 노출 ID로 다운로드. 세션은 부르는 쪽이 붙인다. */
    private MockHttpServletRequestBuilder download(long exposureId) throws Exception {
        return post("/api/v1/coupons").with(csrf(browser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exposureId\":\"" + exposureId + "\"}");
    }

    private long store(String name, String joinStatus) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '" + name + "', 'cafe', 'center', '" + joinStatus + "')");
    }

    /** 확정된 정액 할인 3,000원 행사. */
    private long fixedEvent(long storeId, int total, int issued, int validDays) {
        return insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + storeId + ", 'fixed_discount', 3000, " + validDays + ", "
                + total + ", " + issued + ", UTC_TIMESTAMP(6))");
    }

    private long exposure(long visitor, long baseStoreId, long recommendedStoreId) {
        jdbc.update("""
                INSERT INTO recommendation_exposure
                    (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, page, badge, shown_at)
                VALUES (?, ?, ?, ?, 1, 0, 'visit_data', ?)
                """, visitor, UUID.randomUUID().toString(), baseStoreId, recommendedStoreId, now());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 이 회원이 전에 받은 쿠폰. */
    private void pastCoupon(long eventId, long storeId, LocalDateTime downloadedAt, String status) {
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, customerId, eventId, storeId, downloadedAt, downloadedAt.plusDays(7), status);
    }

    private int issuedCount(long eventId) {
        return jdbc.queryForObject("SELECT issued_count FROM coupon_policy WHERE id = ?", Integer.class, eventId);
    }

    private int couponCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE customer_id = ?", Integer.class, customerId);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** 계정을 넣고 로그인 아이디를 돌려준다. 테스트끼리 같은 DB를 쓰므로 아이디가 겹치지 않게 만든다. */
    private String account(String role, Long storeId, Long regionId) {
        String loginId = "o" + System.nanoTime();
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id, region_id) VALUES (?, ?, ?, ?, ?)",
                loginId, passwordEncoder.encode(PASSWORD), role, storeId, regionId);
        return loginId;
    }

    /** 회원가입이 만드는 회원 손님 정보(C2). */
    private long memberCustomer(String loginId) {
        long accountId = jdbc.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        return insert("INSERT INTO customer (type, account_id) VALUES ('member', " + accountId + ")");
    }

    private MockHttpSession login(String loginId) throws Exception {
        ResultActions result = mvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"));
        return (MockHttpSession) result.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    /**
     * 프론트와 같은 흐름: GET /api/v1/csrf의 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다. 다른 쿠키(방문자)도 함께 싣는다.
     * spring-security-test의 csrf()는 공유 컨텍스트의 CSRF 저장소를 바꿔 다른 테스트를 깨뜨릴 수 있어 쓰지 않는다(AuthApiTest와 같음).
     */
    private RequestPostProcessor csrf(Cookie... others) throws Exception {
        Cookie token = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request -> {
            Cookie[] cookies = new Cookie[others.length + 1];
            cookies[0] = token;
            System.arraycopy(others, 0, cookies, 1, others.length);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
