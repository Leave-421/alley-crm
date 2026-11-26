package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.visitor.VisitorCookie;
import jakarta.servlet.http.Cookie;
import java.sql.Statement;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 쿠폰 다운로드 동시 요청(E9, NFR-09, CONTRACTS_API 3.4 「원자 처리」). 실제 커밋이 필요해 테스트 트랜잭션을 쓰지 않고,
 * 만든 행은 끝나고 지운다. 요청마다 그 회원 브라우저의 방문자 쿠키를 실은 요청 문맥을 스레드에 둔다.
 *
 * <p>NFR-09 #3(다른 회원 ID로 발급 · 적용)은 API가 회원 ID를 받지 않아 세션 계정으로만 정하고, 남의 노출 · 남의 쿠폰은
 * 404다: {@code CouponOfferApiTest.다른_사람의_노출이나_없는_노출이면_404이고_발급_수가_그대로},
 * {@code CouponApplyApiTest.남의_쿠폰이나_없는_쿠폰이면_404이고_예약을_저장하지_않는다}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CouponDownloadConcurrencyTest {

    private static final int REQUESTS = 4;

    @Autowired
    CouponOfferService service;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long baseStore;
    long targetStore;
    long otherTargetStore;
    final List<Long> accounts = new ArrayList<>();
    final List<Long> customers = new ArrayList<>();
    final List<Long> visitors = new ArrayList<>();
    final List<Long> events = new ArrayList<>();

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('쿠폰 동시 다운로드 권역')");
        baseStore = store("A");
        targetStore = store("B");
        otherTargetStore = store("C");
    }

    @AfterEach
    void cleanUp() {
        for (long customer : customers) {
            jdbc.update("DELETE FROM coupon WHERE customer_id = ?", customer);
        }
        for (long visitor : visitors) {
            jdbc.update("DELETE FROM recommendation_exposure WHERE visitor_id = ?", visitor);
            jdbc.update("DELETE FROM visitor WHERE id = ?", visitor);
        }
        for (long customer : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", customer);
        }
        for (long account : accounts) {
            jdbc.update("DELETE FROM account WHERE id = ?", account);
        }
        for (long event : events) {
            jdbc.update("DELETE FROM coupon_policy WHERE id = ?", event);
        }
        jdbc.update("DELETE FROM store WHERE region_id = ?", region);
        jdbc.update("DELETE FROM region WHERE id = ?", region);
    }

    @Test
    void 같은_회원이_같은_행사를_동시에_눌러도_한_장만_발급된다_NFR_09_1() throws Exception {
        long event = event(targetStore, 10);
        Member member = member();
        List<Long> exposures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            exposures.add(exposure(member, targetStore));
        }

        List<ErrorCode> outcomes = runConcurrently(exposures.stream()
                .<Callable<ErrorCode>>map(exposure -> () -> download(member, exposure))
                .toList());

        assertThat(outcomes).filteredOn(code -> code == null).hasSize(1);
        assertThat(outcomes).filteredOn(code -> code != null).containsOnly(ErrorCode.CONFLICT);
        assertThat(couponCount(member)).isEqualTo(1);
        assertThat(issuedCount(event)).isEqualTo(1);
    }

    @Test
    void 남은_한_장을_여러_회원이_동시에_받으면_한_명만_받는다_NFR_09_2() throws Exception {
        long event = event(targetStore, 1);
        List<Member> members = new ArrayList<>();
        List<Long> exposures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            Member member = member();
            members.add(member);
            exposures.add(exposure(member, targetStore));
        }

        List<Callable<ErrorCode>> tasks = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            Member member = members.get(i);
            long exposure = exposures.get(i);
            tasks.add(() -> download(member, exposure));
        }
        List<ErrorCode> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(code -> code == null).hasSize(1);
        assertThat(outcomes).filteredOn(code -> code != null).containsOnly(ErrorCode.CONFLICT);
        assertThat(members.stream().mapToInt(this::couponCount).sum()).isEqualTo(1);
        assertThat(issuedCount(event)).isEqualTo(1);
    }

    @Test
    void 같은_회원이_서로_다른_행사를_동시에_받아도_막히지_않고_둘_다_발급된다() throws Exception {
        long eventB = event(targetStore, 10);
        long eventC = event(otherTargetStore, 10);
        Member member = member();
        long toB = exposure(member, targetStore);
        long toC = exposure(member, otherTargetStore);

        List<ErrorCode> outcomes = runConcurrently(List.of(
                () -> download(member, toB), () -> download(member, toC)));

        assertThat(outcomes).containsOnlyNulls();
        assertThat(couponCount(member)).isEqualTo(2);
        assertThat(issuedCount(eventB)).isEqualTo(1);
        assertThat(issuedCount(eventC)).isEqualTo(1);
    }

    /** 모두 준비된 뒤 한꺼번에 시작한다. 성공은 null, 실패는 에러 코드. */
    private List<ErrorCode> runConcurrently(List<Callable<ErrorCode>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (Callable<ErrorCode> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<ErrorCode> outcomes = new ArrayList<>();
            for (Future<ErrorCode> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 그 회원 브라우저(방문자 쿠키)의 요청으로 다운로드. */
    private ErrorCode download(Member member, long exposure) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(VisitorCookie.NAME, member.anonId().toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        try {
            service.download(member.accountId(), exposure);
            return null;
        } catch (ApiException e) {
            return e.errorCode();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private record Member(long accountId, long customerId, long visitorId, UUID anonId) {
    }

    private Member member() {
        long account = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('q" + System.nanoTime()
                + "', 'x', 'customer')");
        long customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + account + ")");
        UUID anonId = UUID.randomUUID();
        long visitor = insert("INSERT INTO visitor (anon_id) VALUES ('" + anonId + "')");
        accounts.add(account);
        customers.add(customer);
        visitors.add(visitor);
        return new Member(account, customer, visitor, anonId);
    }

    private long exposure(Member member, long recommendedStore) {
        return insert("INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, "
                + "`rank`, page, badge, shown_at) VALUES (" + member.visitorId() + ", '" + UUID.randomUUID() + "', "
                + baseStore + ", " + recommendedStore + ", 1, 0, 'visit_data', UTC_TIMESTAMP(6))");
    }

    private long event(long storeId, int totalQuantity) {
        long event = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + storeId + ", 'fixed_discount', 3000, 7, " + totalQuantity
                + ", 0, UTC_TIMESTAMP(6))");
        events.add(event);
        return event;
    }

    private long store(String name) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '" + name + "', 'cafe', 'center', 'approved')");
    }

    private int couponCount(Member member) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE customer_id = ?", Integer.class, member.customerId());
    }

    private int issuedCount(long event) {
        return jdbc.queryForObject("SELECT issued_count FROM coupon_policy WHERE id = ?", Integer.class, event);
    }

    private long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }
}
