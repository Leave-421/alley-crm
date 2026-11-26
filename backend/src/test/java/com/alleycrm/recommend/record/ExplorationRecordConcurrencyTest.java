package com.alleycrm.recommend.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.domain.RecommendationBadge;
import com.alleycrm.recommend.visitor.VisitorCookie;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 같은 노출 · 같은 제안 요청이 동시에 와도 1건만 생기고 모두 같은 ID를 받는다(CONTRACTS_API 3.1 · 3.3).
 * 실제 커밋이 필요해 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝나고 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExplorationRecordConcurrencyTest {

    private static final int REQUESTS = 4;

    @Autowired
    ExplorationRecordService service;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long baseStore;
    long store;
    long customer;
    long reservation;
    long visitor;
    String anonId;

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('탐색 동시성 권역')");
        baseStore = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", '기준', 'cafe', 'center', 'approved')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES (" + region + ", 'A', 'pub', 'center', 'approved')");
        customer = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님', '01044440000')");
        reservation = insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at) "
                + "VALUES ('E3-C1', " + baseStore + ", " + customer + ", NOW(6), 2, NOW(6))");
        anonId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO visitor (anon_id) VALUES (?)", anonId);
        visitor = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM recommendation_exposure WHERE visitor_id = ?", visitor);
        jdbc.update("DELETE FROM course_proposal WHERE base_reservation_id = ?", reservation);
        jdbc.update("DELETE FROM reservation WHERE id = ?", reservation);
        jdbc.update("DELETE FROM visitor WHERE id = ?", visitor);
        jdbc.update("DELETE FROM customer WHERE id = ?", customer);
        jdbc.update("DELETE FROM store WHERE region_id = ?", region);
        jdbc.update("DELETE FROM region WHERE id = ?", region);
    }

    @Test
    void 같은_표시의_노출이_동시에_와도_1건() throws Exception {
        UUID display = UUID.randomUUID();
        ExposureItem item = new ExposureItem(store, 1, RecommendationBadge.LOCAL_POPULAR, ReasonType.DISTANCE);

        List<Long> ids = runConcurrently(() -> {
            bindBrowser(anonId);
            try {
                return service.recordExposures(display, baseStore, 0, List.of(item)).get(0).exposureId();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_exposure WHERE visitor_id = ?",
                Integer.class, visitor)).isEqualTo(1);
    }

    @Test
    void 같은_정식_제안이_동시에_와도_1건() throws Exception {
        List<Long> ids = runConcurrently(() ->
                service.recordProposals(reservation, List.of(store)).get(0).courseProposalId());

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_proposal WHERE base_reservation_id = ?",
                Integer.class, reservation)).isEqualTo(1);
    }

    private static List<Long> runConcurrently(Callable<Long> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            return ids;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void bindBrowser(String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(VisitorCookie.NAME, cookieValue));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
