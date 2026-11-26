package com.alleycrm.recommend.todaycourse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.reservation.request.SlotRules;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 오늘의 코스 API(CONTRACTS_API 3.7, FR-CRS-11 · 12). 로그인 없이 여는 보안 설정(C, T-1) 전이라 회원 세션으로 부른다.
 * 지금은 2026-11-28(토) 18:30 KST — 주말 저녁 칸.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TodayCourseApiTest {

    private static final String PASSWORD = "password1";
    private static final LocalDateTime SATURDAY_18_KST = LocalDateTime.of(2026, 11, 28, 18, 0);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accounts;

    @Autowired
    AppClock clock;

    long region;
    long a;
    long b;
    long c;
    long f;
    MockHttpSession member;

    @BeforeEach
    void seed() throws Exception {
        clock.setTo(SATURDAY_18_KST.plusMinutes(30).atZone(SlotRules.SERVICE_ZONE).toInstant());
        region = insert("INSERT INTO region (name) VALUES ('오늘의 코스 권역')");
        a = store("가 카페");
        b = store("나 디저트");
        c = store("다 포차");
        f = store("라 서점");
        String loginId = "t" + System.nanoTime();
        accounts.signupCustomer(loginId, PASSWORD, "회원", null, null);
        member = login(loginId);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void 코스는_가게_3곳_배지_쿠폰_참여_가게_수를_주고_손님_수는_없다_FR_CRS_12_1() throws Exception {
        route(6, a, b, c);
        route(5, f, b, c);
        insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + b + ", 'fixed_discount', 3000, 7, 10, 0, UTC_TIMESTAMP(6))");

        todayCourses(region)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.dayType").value("weekend"))
                .andExpect(jsonPath("$.timeBand").value("evening"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].stores[*].id").value(Matchers.contains(id(a), id(b), id(c))))
                .andExpect(jsonPath("$.items[0].stores[0].name").value("가 카페"))
                .andExpect(jsonPath("$.items[0].stores[0].alley").value("center"))
                .andExpect(jsonPath("$.items[0].badge").value("visit_data"))
                .andExpect(jsonPath("$.items[0].couponStoreCount").value(1))
                .andExpect(jsonPath("$.items[1].stores[*].id").value(Matchers.contains(id(f), id(b), id(c))))
                .andExpect(jsonPath("$.items[0].customerCount").doesNotExist())
                .andExpect(jsonPath("$.items[0].count").doesNotExist());
    }

    @Test
    void 중간_가게를_취소한_손님은_세지_않는다_FR_CRS_11_5() throws Exception {
        route(4, a, b, c);
        for (int i = 0; i < 2; i++) {
            long customer = insert("INSERT INTO customer (type) VALUES ('member')");
            reservation(customer, a, SATURDAY_18_KST, "visited");
            reservation(customer, b, SATURDAY_18_KST.plusMinutes(30), "canceled");
            reservation(customer, c, SATURDAY_18_KST.plusMinutes(60), "visited");
        }

        todayCourses(region).andExpect(jsonPath("$.items").isEmpty()); // 4명 → 5명 미만
    }

    @Test
    void 휴업한_가게가_든_동선은_빠지고_다음_순위가_올라온다_FR_CRS_11_3() throws Exception {
        route(7, a, b, c);
        route(5, f, a, c);
        jdbc.update("UPDATE store SET business_status = 'paused' WHERE id = ?", b);

        todayCourses(region)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].stores[*].id").value(Matchers.contains(id(f), id(a), id(c))));
    }

    @Test
    void 다른_칸의_동선만_있으면_없다_FR_CRS_11_4() throws Exception {
        route(8, a, b, c);
        clock.setTo(Instant.parse("2026-11-30T09:30:00Z")); // 월요일 18:30 KST — 평일 저녁

        todayCourses(region)
                .andExpect(jsonPath("$.dayType").value("weekday"))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 권역이_없으면_404_값이_틀리면_400() throws Exception {
        todayCourses(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        for (String q : new String[] {"", "regionId=abc", "regionId=0"}) {
            mvc.perform(get("/api/v1/today-courses?" + q).session(member))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    // ---- 준비 ----

    private ResultActions todayCourses(long regionId) throws Exception {
        return mvc.perform(get("/api/v1/today-courses").param("regionId", String.valueOf(regionId)).session(member));
    }

    /** {@code count}명이 토요일 18:00 KST부터 30분 간격으로 세 가게를 이어 방문 처리됨. */
    private void route(int count, long first, long second, long third) {
        for (int i = 0; i < count; i++) {
            long customer = insert("INSERT INTO customer (type) VALUES ('member')");
            reservation(customer, first, SATURDAY_18_KST, "visited");
            reservation(customer, second, SATURDAY_18_KST.plusMinutes(30), "visited");
            reservation(customer, third, SATURDAY_18_KST.plusMinutes(60), "visited");
        }
    }

    private void reservation(long customer, long store, LocalDateTime startKst, String status) {
        LocalDateTime startUtc = LocalDateTime.ofInstant(startKst.atZone(SlotRules.SERVICE_ZONE).toInstant(),
                ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), store, customer, startUtc, status,
                startUtc.minusDays(1));
    }

    private long store(String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, map_x, map_y) VALUES ("
                + region + ", '" + name + "', 'cafe', 'center', 'approved', 0, 0)");
    }

    private static String id(long value) {
        return String.valueOf(value);
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
