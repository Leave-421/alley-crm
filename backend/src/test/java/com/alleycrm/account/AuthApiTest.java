package com.alleycrm.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** C2 회원가입 · 로그인 · 로그아웃 · 내 계정 (FR-AUTH-01 · 02 · 05). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accountService;

    /** 테스트끼리 같은 DB를 쓰므로 아이디가 겹치지 않게 만든다. */
    private static String newLoginId() {
        return "u" + System.nanoTime();
    }

    /**
     * 프론트와 같은 흐름: GET /api/v1/csrf의 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다.
     * spring-security-test의 csrf()는 공유 컨텍스트의 CsrfFilter 저장소를 바꿔 다른 테스트(ApiBaseTest)를 깨뜨려서 쓰지 않는다.
     */
    private RequestPostProcessor csrf() throws Exception {
        Cookie token = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        return request -> {
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    private void signup(String loginId, String extraJson) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"password1\",\"name\":\"홍길동\"" + extraJson + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value(loginId))
                .andExpect(jsonPath("$.role").value("customer"))
                .andExpect(jsonPath("$.storeId").isEmpty())
                .andExpect(jsonPath("$.regionId").isEmpty());
    }

    private MockHttpSession login(String loginId, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void 회원가입하면_성별_연령대가_회원_손님_정보에_들어간다() throws Exception {
        String loginId = newLoginId();
        signup(loginId, ",\"gender\":\"female\",\"ageGroup\":\"age_30s\"");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT c.type, c.name, c.gender, c.age_group, a.password_hash
                FROM customer c JOIN account a ON a.id = c.account_id WHERE a.login_id = ?""", loginId);
        assertThat(row.get("type")).isEqualTo("member");
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(row.get("gender")).isEqualTo("female");
        assertThat(row.get("age_group")).isEqualTo("age_30s");
        assertThat((String) row.get("password_hash")).startsWith("{bcrypt}").doesNotContain("password1");
    }

    @Test
    void 성별_연령대는_생략할_수_있다() throws Exception {
        String loginId = newLoginId();
        signup(loginId, "");
        assertThat(jdbc.queryForMap("SELECT c.gender, c.age_group FROM customer c JOIN account a ON a.id = c.account_id WHERE a.login_id = ?", loginId))
                .containsEntry("gender", null).containsEntry("age_group", null);
    }

    @Test
    void 가입은_역할을_보내도_항상_손님_계정이다() throws Exception {
        // FR-AUTH-05 #2: 상인회 계정 가입 기능이 없다
        String loginId = newLoginId();
        signup(loginId, ",\"role\":\"association\",\"regionId\":\"1\"");
    }

    @Test
    void 이미_쓰는_아이디는_409_conflict() throws Exception {
        String loginId = newLoginId();
        signup(loginId, "");
        mvc.perform(post("/api/v1/auth/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"password1\",\"name\":\"김\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 잘못된_입력은_400_invalid() throws Exception {
        for (String body : new String[] {
                "{\"loginId\":\"AB\",\"password\":\"password1\",\"name\":\"김\"}",
                "{\"loginId\":\"abcd1\",\"password\":\"short\",\"name\":\"김\"}",
                "{\"loginId\":\"abcd1\",\"password\":\"password1\",\"name\":\"\"}",
                "{\"loginId\":\"abcd1\",\"password\":\"password1\",\"name\":\"김\",\"gender\":\"other\"}",
                "{\"loginId\":\"abcd1\",\"password\":\"password1\",\"name\":\"김\",\"ageGroup\":\"age_10s\"}"}) {
            mvc.perform(post("/api/v1/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void CSRF_토큰_없는_가입은_403_csrf_invalid() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    @Test
    void 로그인하면_세션으로_내_계정을_보고_로그아웃하면_401() throws Exception {
        String loginId = newLoginId();
        signup(loginId, "");
        MockHttpSession session = login(loginId, "password1");

        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value(loginId))
                .andExpect(jsonPath("$.role").value("customer"))
                .andExpect(jsonPath("$.id").isString());

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인하면_세션_ID가_바뀐다() throws Exception {
        String loginId = newLoginId();
        signup(loginId, "");
        MockHttpSession before = new MockHttpSession();
        String beforeId = before.getId();
        MockHttpSession after = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").session(before).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
        assertThat(after.getId()).isNotEqualTo(beforeId);
    }

    @Test
    void 비밀번호가_틀리거나_없는_아이디면_같은_401_unauthorized() throws Exception {
        String loginId = newLoginId();
        signup(loginId, "");
        for (String body : new String[] {
                "{\"loginId\":\"" + loginId + "\",\"password\":\"wrongpass\"}",
                "{\"loginId\":\"nobody" + loginId + "\",\"password\":\"password1\"}"}) {
            mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("unauthorized"))
                    .andExpect(jsonPath("$.error.message").value("아이디 또는 비밀번호가 맞지 않습니다."));
        }
    }

    @Test
    void 빈_로그인_입력은_400_invalid() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 로그인_안_하고_로그아웃하면_401() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 초기_데이터_상인회_계정으로_로그인하면_그_권역의_상인회다() throws Exception {
        // FR-AUTH-05 #1
        jdbc.update("INSERT INTO region (name) VALUES ('상인회 테스트 권역')");
        long regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String loginId = newLoginId();
        accountService.createAssociation(regionId, loginId, "assocpass1");

        MockHttpSession session = login(loginId, "assocpass1");
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(jsonPath("$.role").value("association"))
                .andExpect(jsonPath("$.regionId").value(String.valueOf(regionId)))
                .andExpect(jsonPath("$.storeId").isEmpty());
    }
}
