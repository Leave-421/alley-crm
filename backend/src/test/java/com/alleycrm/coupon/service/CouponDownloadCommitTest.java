package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.visitor.VisitorCookie;
import jakarta.servlet.http.Cookie;
import java.sql.Statement;
import java.util.UUID;
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
 * 다운로드를 테스트 트랜잭션 없이 실제로 커밋한다. 서비스 자신의 트랜잭션(READ COMMITTED)으로 발급 수 UPDATE →
 * 잠그는 읽기 → 저장이 돌고, 거절되면 발급 수까지 되돌아가는지 본다. 동시 요청 검증은 E9(#106).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CouponDownloadCommitTest {

    @Autowired
    CouponOfferService service;

    @Autowired
    CouponApplyService applyService;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long baseStore;
    long targetStore;
    long event;
    long account;
    long customer;
    long visitor;
    UUID anonId;

    @BeforeEach
    void seed() {
        region = insert("INSERT INTO region (name) VALUES ('쿠폰 커밋 권역')");
        baseStore = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", 'A', 'cafe', 'center', 'approved')");
        targetStore = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", 'B', 'pub', 'center', 'approved')");
        event = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + targetStore + ", 'fixed_discount', 3000, 7, 5, 0, UTC_TIMESTAMP(6))");
        account = insert("INSERT INTO account (login_id, password_hash, role) VALUES ('k" + System.nanoTime()
                + "', 'x', 'customer')");
        customer = insert("INSERT INTO customer (type, account_id) VALUES ('member', " + account + ")");
        anonId = UUID.randomUUID();
        visitor = insert("INSERT INTO visitor (anon_id) VALUES ('" + anonId + "')");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(VisitorCookie.NAME, anonId.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("DELETE FROM coupon WHERE customer_id = ?", customer);
        jdbc.update("DELETE FROM recommendation_exposure WHERE visitor_id = ?", visitor);
        jdbc.update("DELETE FROM visitor WHERE id = ?", visitor);
        jdbc.update("DELETE FROM customer WHERE id = ?", customer);
        jdbc.update("DELETE FROM account WHERE id = ?", account);
        jdbc.update("DELETE FROM coupon_policy WHERE id = ?", event);
        jdbc.update("DELETE FROM store WHERE region_id = ?", region);
        jdbc.update("DELETE FROM region WHERE id = ?", region);
    }

    @Test
    void 받으면_커밋되고_다시_받으면_409이며_발급_수가_되돌아간다() {
        service.download(account, exposure());

        assertThat(issuedCount()).isEqualTo(1);
        assertThat(couponCount()).isEqualTo(1);

        long again = exposure();
        assertThatThrownBy(() -> service.download(account, again))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(issuedCount()).isEqualTo(1);
        assertThat(couponCount()).isEqualTo(1);
    }

    @Test
    void 예약_화면의_사용_가능한_쿠폰은_읽기_전용_트랜잭션에서_잠그지_않고_읽는다() {
        // 테스트 트랜잭션 없이 부른다. 읽기 전용 트랜잭션에서 SELECT ... FOR UPDATE를 내면 500이었다(#261 회귀)
        service.download(account, exposure());

        assertThat(applyService.usable(account, targetStore).items()).hasSize(1);
    }

    private long exposure() {
        return insert("INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, "
                + "`rank`, page, badge, shown_at) VALUES (" + visitor + ", '" + UUID.randomUUID() + "', " + baseStore
                + ", " + targetStore + ", 1, 0, 'visit_data', UTC_TIMESTAMP(6))");
    }

    private int issuedCount() {
        return jdbc.queryForObject("SELECT issued_count FROM coupon_policy WHERE id = ?", Integer.class, event);
    }

    private int couponCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE customer_id = ?", Integer.class, customer);
    }

    private long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }
}
