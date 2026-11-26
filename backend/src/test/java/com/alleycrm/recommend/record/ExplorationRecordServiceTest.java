package com.alleycrm.recommend.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.domain.RecommendationBadge;
import com.alleycrm.recommend.visitor.VisitorCookie;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/** 탐색 기록 · 정식 코스 제안 저장(FR-CRS-07 #1, CONTRACTS_API 3.1 · 3.3). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ExplorationRecordServiceTest {

    private static final Instant T1 = Instant.parse("2026-12-04T09:00:00Z");

    @Autowired
    ExplorationRecordService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppClock clock;

    long baseStore;
    long storeA;
    long storeB;
    long hiddenStore;
    long baseReservation;

    @BeforeEach
    void seed() {
        long region = insert("INSERT INTO region (name) VALUES ('탐색 기록 권역')");
        baseStore = store(region, "기준", "approved");
        storeA = store(region, "A", "approved");
        storeB = store(region, "B", "approved");
        hiddenStore = store(region, "승인 전", "requested");
        long customer = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님', '01033330000')");
        baseReservation = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at) "
                + "VALUES ('E3-R1', " + baseStore + ", " + customer + ", NOW(6), 2, NOW(6))");
    }

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
        clock.reset();
    }

    @Test
    void 가게_클릭은_방문자_ID로_누를_때마다_기록한다() {
        MockHttpServletResponse first = browse(null);
        service.recordStoreClick(storeA);
        String anonId = cookieValue(first);

        browse(anonId);
        service.recordStoreClick(storeA);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM store_click c JOIN visitor v ON v.id = c.visitor_id
                WHERE v.anon_id = ? AND c.store_id = ?
                """, Integer.class, anonId, storeA)).isEqualTo(2);
    }

    @Test
    void 손님에게_보이지_않는_가게_클릭은_404이고_방문자도_만들지_않는다() {
        int visitorsBefore = count("SELECT COUNT(*) FROM visitor");
        MockHttpServletResponse response = browse(null);

        assertThatThrownBy(() -> service.recordStoreClick(hiddenStore))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.recordStoreClick(999_999L)).isInstanceOf(ApiException.class);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(count("SELECT COUNT(*) FROM visitor")).isEqualTo(visitorsBefore);
    }

    @Test
    void 로그인_전_클릭과_노출은_같은_방문자로_따로_기록된다() {
        MockHttpServletResponse response = browse(null);
        service.recordStoreClick(baseStore);
        service.recordExposures(UUID.randomUUID(), baseStore, 0, List.of(item(storeA, 1)));

        String anonId = cookieValue(response);
        Long visitorId = jdbc.queryForObject("SELECT id FROM visitor WHERE anon_id = ?", Long.class, anonId);
        assertThat(count("SELECT COUNT(*) FROM store_click WHERE visitor_id = " + visitorId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM recommendation_exposure WHERE visitor_id = " + visitorId)).isEqualTo(1);
    }

    @Test
    void 같은_표시의_재전송은_같은_노출이고_다시_그리면_새_노출이다() {
        MockHttpServletResponse issued = browse(null);
        UUID display = UUID.randomUUID();
        List<ExposureItem> items = List.of(item(storeA, 1), item(storeB, 2));

        List<RecordedExposure> first = service.recordExposures(display, baseStore, 0, items);
        browse(cookieValue(issued));
        List<RecordedExposure> resent = service.recordExposures(display, baseStore, 0, items);
        List<RecordedExposure> redrawn = service.recordExposures(UUID.randomUUID(), baseStore, 1, items);

        assertThat(resent).isEqualTo(first);
        assertThat(redrawn).extracting(RecordedExposure::exposureId)
                .doesNotContainAnyElementsOf(first.stream().map(RecordedExposure::exposureId).toList());
        assertThat(count("SELECT COUNT(*) FROM recommendation_exposure WHERE base_store_id = " + baseStore)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT badge FROM recommendation_exposure WHERE id = ?", String.class,
                first.get(0).exposureId())).isEqualTo("visit_data");
    }

    @Test
    void 정식_제안은_조합당_1건이고_처음_표시_시각을_유지한다() {
        clock.setTo(T1);
        RecordedProposal a = service.recordProposals(baseReservation, List.of(storeA)).get(0);
        clock.setTo(T1.plusSeconds(3600));

        List<RecordedProposal> again = service.recordProposals(baseReservation, List.of(storeA, storeB));

        assertThat(again.get(0)).isEqualTo(a);
        assertThat(toInstant(a.firstShownAt())).isBetween(T1, T1.plusSeconds(10));
        assertThat(toInstant(again.get(1).firstShownAt())).isAfterOrEqualTo(T1.plusSeconds(3600));
        assertThat(count("SELECT COUNT(*) FROM course_proposal WHERE base_reservation_id = " + baseReservation))
                .isEqualTo(2);
    }

    private static ExposureItem item(long storeId, int rank) {
        return new ExposureItem(storeId, rank, RecommendationBadge.VISIT_DATA, ReasonType.VISIT_DATA);
    }

    private static MockHttpServletResponse browse(String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (cookieValue != null) {
            request.setCookies(new Cookie(VisitorCookie.NAME, cookieValue));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return response;
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith(VisitorCookie.NAME + "=");
        return header.substring(VisitorCookie.NAME.length() + 1, header.indexOf(';'));
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }

    private long store(long region, String name, String joinStatus) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '" + name + "', 'cafe', 'center', '" + joinStatus + "')");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
