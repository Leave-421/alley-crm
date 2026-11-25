package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 같은 가게에서 만들기 요청이 동시에 들어와도 진행 중인 행사는 하나만 생긴다(FR-CPN-01 #6).
 * 실제 커밋이 필요해 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝나고 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CouponEventConcurrencyTest {

    private static final int REQUESTS = 4;

    @Autowired
    CouponEventService service;

    @Autowired
    JdbcTemplate jdbc;

    long regionId;
    long storeId;

    @BeforeEach
    void seed() {
        regionId = insert("INSERT INTO region (name) VALUES ('쿠폰 동시성 권역')");
        storeId = insert("INSERT INTO store (region_id, name, category, alley) VALUES (" + regionId + ", 'C', 'cafe', 'center')");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM coupon_policy WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void 동시에_만들어도_진행_중인_행사는_하나() throws Exception {
        CouponEventCreateRequest request = new CouponEventCreateRequest(
                "fixed_discount", 1000, null, null, null, null, null, null, 10);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<Future<ErrorCode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.create(storeId, request);
                        return null;
                    } catch (ApiException e) {
                        return e.errorCode();
                    }
                }));
            }
            start.countDown();

            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(code -> code == null).hasSize(1);
            assertThat(outcomes).filteredOn(code -> code != null).containsOnly(ErrorCode.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_policy WHERE store_id = ? AND stopped_at IS NULL", Integer.class, storeId))
                .isEqualTo(1);
    }

    private long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }
}
