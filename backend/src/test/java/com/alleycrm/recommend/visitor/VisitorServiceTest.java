package com.alleycrm.recommend.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 익명 방문자 발급 · 손님 잇기(FR-CRS-07 #2 · #3, CONTRACTS_API 3.3). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class VisitorServiceTest {

    @Autowired
    VisitorService service;

    @Autowired
    JdbcTemplate jdbc;

    long customerA;
    long customerB;

    @BeforeEach
    void seed() {
        customerA = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님A', '01011110000')");
        customerB = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님B', '01022220000')");
    }

    @AfterEach
    void unbind() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 쿠키가_없으면_새_방문자를_만들고_쿠키를_싣는다() {
        MockHttpServletResponse response = browse(null);

        long id = service.currentVisitorId();

        UUID anonId = issuedAnonId(response);
        assertThat(jdbc.queryForObject("SELECT id FROM visitor WHERE anon_id = ?", Long.class, anonId.toString()))
                .isEqualTo(id);
    }

    @Test
    void 같은_쿠키면_같은_방문자이고_쿠키를_다시_싣지_않는다() {
        MockHttpServletResponse first = browse(null);
        long id = service.currentVisitorId();
        UUID anonId = issuedAnonId(first);

        MockHttpServletResponse second = browse(anonId.toString());

        assertThat(service.currentVisitorId()).isEqualTo(id);
        assertThat(second.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 서버에_없는_ID나_UUID가_아닌_값이면_새로_발급한다() {
        String unknown = UUID.randomUUID().toString();
        MockHttpServletResponse response = browse(unknown);
        service.currentVisitorId();
        assertThat(issuedAnonId(response).toString()).isNotEqualTo(unknown);
        assertThat(count("SELECT COUNT(*) FROM visitor WHERE anon_id = ?", unknown)).isZero();

        MockHttpServletResponse garbage = browse("abc");
        service.currentVisitorId();
        assertThat(issuedAnonId(garbage)).isNotNull();
    }

    @Test
    void 같은_브라우저에서_예약하면_그_방문자를_손님에_잇는다() {
        UUID anonId = newVisitor();
        MockHttpServletResponse response = browse(anonId.toString());

        service.linkCurrentBrowser(customerA);

        assertThat(customerOf(anonId)).isEqualTo(customerA);
        assertThat(jdbc.queryForObject("SELECT linked_at IS NOT NULL FROM visitor WHERE anon_id = ?", Boolean.class,
                anonId.toString())).isTrue();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 다른_기기에서_예약하면_잇지_않는다() {
        UUID otherDevice = newVisitor();
        browse(null); // 이 기기에는 쿠키가 없다

        service.linkCurrentBrowser(customerA);

        assertThat(customerOf(otherDevice)).isNull();
        assertThat(count("SELECT COUNT(*) FROM visitor WHERE customer_id = ?", customerA)).isZero();
    }

    @Test
    void 이미_같은_손님이면_그대로_둔다() {
        UUID anonId = newVisitor();
        browse(anonId.toString());
        service.linkCurrentBrowser(customerA);

        MockHttpServletResponse again = browse(anonId.toString());
        service.linkCurrentBrowser(customerA);

        assertThat(again.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(count("SELECT COUNT(*) FROM visitor WHERE customer_id = ?", customerA)).isEqualTo(1);
    }

    @Test
    void 다른_손님에_이어진_브라우저면_기존_연결은_두고_새_방문자를_발급한다() {
        UUID shared = newVisitor();
        browse(shared.toString());
        service.linkCurrentBrowser(customerA);

        MockHttpServletResponse response = browse(shared.toString());
        service.linkCurrentBrowser(customerB);

        assertThat(customerOf(shared)).isEqualTo(customerA);
        UUID fresh = issuedAnonId(response);
        assertThat(fresh).isNotEqualTo(shared);
        assertThat(customerOf(fresh)).isEqualTo(customerB);
    }

    @Test
    void 로그아웃하면_방문자_쿠키를_지운다() {
        MockHttpServletResponse response = browse(UUID.randomUUID().toString());

        service.forgetCurrentBrowser();

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("alley_vid=;").contains("Max-Age=0");
    }

    @Test
    void HTTP_요청_밖에서는_부를_수_없다() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> service.currentVisitorId()).isInstanceOf(IllegalStateException.class);
    }

    /** 새 방문자를 만들고 그 쿠키 값을 돌려준다. */
    private UUID newVisitor() {
        MockHttpServletResponse response = browse(null);
        service.currentVisitorId();
        return issuedAnonId(response);
    }

    /** 이 쿠키를 가진 브라우저의 요청을 현재 요청으로 둔다. */
    private static MockHttpServletResponse browse(String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (cookieValue != null) {
            request.setCookies(new Cookie(VisitorCookie.NAME, cookieValue));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return response;
    }

    private static UUID issuedAnonId(MockHttpServletResponse response) {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("방문자 쿠키 발급").startsWith(VisitorCookie.NAME + "=");
        return UUID.fromString(header.substring(VisitorCookie.NAME.length() + 1, header.indexOf(';')));
    }

    private Long customerOf(UUID anonId) {
        return jdbc.queryForObject("SELECT customer_id FROM visitor WHERE anon_id = ?", Long.class, anonId.toString());
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
