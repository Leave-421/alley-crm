package com.alleycrm.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 행사 설정 API(CONTRACTS_API 3.2, FR-CPN-01 · 11). 로그인 · 역할 · 가게 격리 · CSRF와 응답 모양을 본다.
 * 계정은 DB에 넣고 실제 로그인 흐름으로 세션을 받는다(사장님 가입 API는 C5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponEventApiTest {

    private static final String PASSWORD = "password1";
    private static final String PERCENT_EVENT = """
            {"benefitType":"percent_discount","discountPercent":10,"maxDiscountAmount":5000,"validDays":3,"totalQuantity":50}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    long storeA;
    long storeB;
    MockHttpSession ownerA;
    MockHttpSession ownerB;
    MockHttpSession customer;
    MockHttpSession association;

    @BeforeEach
    void seed() throws Exception {
        long region = insert("INSERT INTO region (name) VALUES ('쿠폰 API 권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'cafe', 'center', 'approved')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'B', 'pub', 'center', 'approved')");
        ownerA = login(account("owner", storeA, null));
        ownerB = login(account("owner", storeB, null));
        customer = login(account("customer", null, null));
        association = login(account("association", null, region));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(get("/api/v1/coupon-events/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void 손님과_상인회는_403() throws Exception {
        mvc.perform(get("/api/v1/coupon-events/benefit-types").session(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
        mvc.perform(post("/api/v1/coupon-events").session(association).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden_role"));
    }

    @Test
    void 혜택_유형은_정액_정률_서비스_세_가지() throws Exception {
        mvc.perform(get("/api/v1/coupon-events/benefit-types").session(ownerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].value",
                        Matchers.contains("fixed_discount", "percent_discount", "service")));
    }

    @Test
    void 만들면_201이고_내_가게의_진행_중인_행사로_보인다() throws Exception {
        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(Matchers.nullValue()));

        mvc.perform(post("/api/v1/coupon-events").session(ownerA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.storeId").value(String.valueOf(storeA)))
                .andExpect(jsonPath("$.benefitType").value("percent_discount"))
                .andExpect(jsonPath("$.discountPercent").value(10))
                .andExpect(jsonPath("$.maxDiscountAmount").value(5000))
                .andExpect(jsonPath("$.discountAmount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.validDays").value(3))
                .andExpect(jsonPath("$.remainingQuantity").value(50))
                .andExpect(jsonPath("$.confirmedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.stoppedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.benefitAvailable").value(true));

        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerA))
                .andExpect(jsonPath("$.current.discountPercent").value(10));
        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerB))
                .andExpect(jsonPath("$.current").value(Matchers.nullValue()));
    }

    @Test
    void 진행_중인_행사가_있으면_하나_더_만들면_409() throws Exception {
        create(ownerA);

        mvc.perform(post("/api/v1/coupon-events").session(ownerA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 검사에_걸리면_400_invalid() throws Exception {
        mvc.perform(post("/api/v1/coupon-events").session(ownerA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"benefitType\":\"service\",\"serviceName\":\"아메리카노\",\"totalQuantity\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 중단하면_200이고_진행_중인_행사가_없어진다() throws Exception {
        String id = create(ownerA);

        mvc.perform(post("/api/v1/coupon-events/" + id + "/stop").session(ownerA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stoppedAt").value(Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.benefitAvailable").value(false));
        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerA))
                .andExpect(jsonPath("$.current").value(Matchers.nullValue()));
        mvc.perform(post("/api/v1/coupon-events/" + id + "/stop").session(ownerA).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void 다른_가게_행사나_없는_행사를_중단하면_404() throws Exception {
        String id = create(ownerA);

        for (String target : new String[] {id, "999999999", "abc"}) {
            mvc.perform(post("/api/v1/coupon-events/" + target + "/stop").session(ownerB).with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerA))
                .andExpect(jsonPath("$.current.stoppedAt").value(Matchers.nullValue()));
    }

    @Test
    void 고치는_API는_없다() throws Exception {
        String id = create(ownerA);

        mvc.perform(put("/api/v1/coupon-events/mine").session(ownerA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/coupon-events/" + id).session(ownerA).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"totalQuantity\":999}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/coupon-events/mine").session(ownerA))
                .andExpect(jsonPath("$.current.totalQuantity").value(50));
    }

    @Test
    void CSRF_토큰이_없으면_403_csrf_invalid() throws Exception {
        mvc.perform(post("/api/v1/coupon-events").session(ownerA)
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    private String create(MockHttpSession owner) throws Exception {
        String json = mvc.perform(post("/api/v1/coupon-events").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PERCENT_EVENT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
    }

    /** 계정을 넣고 로그인 아이디를 돌려준다. 테스트끼리 같은 DB를 쓰므로 아이디가 겹치지 않게 만든다. */
    private String account(String role, Long storeId, Long regionId) {
        String loginId = "c" + System.nanoTime();
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id, region_id) VALUES (?, ?, ?, ?, ?)",
                loginId, passwordEncoder.encode(PASSWORD), role, storeId, regionId);
        return loginId;
    }

    private MockHttpSession login(String loginId) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    /**
     * 프론트와 같은 흐름: GET /api/v1/csrf의 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다.
     * spring-security-test의 csrf()는 공유 컨텍스트의 CSRF 저장소를 바꿔 다른 테스트를 깨뜨릴 수 있어 쓰지 않는다(AuthApiTest와 같음).
     */
    private RequestPostProcessor csrf() throws Exception {
        Cookie token = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request -> {
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
