package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.AppClock;
import java.time.Instant;
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

/**
 * 상인회 쿠폰 선착순 받기 동시성(CONTRACTS_API 3.9 「원자 처리」, NFR-09). 실제 커밋 · 잠금을 보려고 테스트 트랜잭션 없이
 * 서비스를 여러 스레드에서 동시에 부르고, 이 테스트가 만든 행만 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AssociationCouponClaimConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-11-30T03:00:00Z");

    @Autowired
    AssociationCouponOfferService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppClock clock;

    long region;
    long event;
    final List<Long> customers = new ArrayList<>();

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        region = insert("INSERT INTO region (name) VALUES ('상인회 쿠폰 동시성 권역')");
        event = insert("""
                INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_amount, total_quantity,
                    eligible_store_scope, issue_start_at, issue_end_at, valid_start_at, valid_end_at, reserved_budget, confirmed_at)
                VALUES (%d, '선착순', 'fixed_discount', 5000, 3, 'all', '2026-11-30 00:00:00', '2026-12-07 00:00:00',
                    '2026-11-30 00:00:00', '2026-12-14 00:00:00', 15000, '2026-11-30 00:00:00')
                """.formatted(region));
        for (int i = 0; i < 8; i++) {
            customers.add(insert("INSERT INTO customer (type) VALUES ('member')"));
        }
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM coupon WHERE association_event_id = ?", event);
        jdbc.update("DELETE FROM association_coupon_event WHERE id = ?", event);
        jdbc.update("DELETE FROM region WHERE id = ?", region);
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
    }

    @Test
    void 회원_8명이_수량_3장_행사를_동시에_받으면_정확히_3명만_받는다() throws Exception {
        int succeeded = race(customers);

        assertThat(succeeded).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM association_coupon_event WHERE id = ?", Integer.class, event))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE association_event_id = ?", Integer.class, event))
                .isEqualTo(3);
    }

    @Test
    void 같은_회원이_동시에_여러_번_눌러도_한_장이고_받은_수도_하나() throws Exception {
        long one = customers.get(0);

        int succeeded = race(List.of(one, one, one, one, one));

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM association_coupon_event WHERE id = ?", Integer.class, event))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE association_event_id = ? AND customer_id = ?",
                Integer.class, event, one)).isEqualTo(1);
    }

    /** 모든 스레드가 준비된 뒤 한꺼번에 받기. 성공한 수. 실패는 모두 409여야 한다. */
    private int race(List<Long> claimers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(claimers.size());
        CountDownLatch ready = new CountDownLatch(claimers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (long customer : claimers) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.claim(event, customer);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    return false;
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        int succeeded = 0;
        for (Future<Boolean> r : results) {
            if (r.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();
        return succeeded;
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
