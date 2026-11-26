package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.coupon.domain.BenefitType;
import com.alleycrm.coupon.dto.CouponEventCreateRequest;
import com.alleycrm.coupon.dto.CouponEventResponse;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;

/** 쿠폰 행사 만들기 · 조회 · 중단(E11, FR-CPN-01 · 11, CONTRACTS_API 3.2). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-01T03:00:00Z");

    /** AppClock.setTo는 시각을 고정하지 않고 그 시각부터 계속 흐르게 하므로 저장 시각은 조금 뒤가 된다. */
    private static final Duration CLOCK_DRIFT = Duration.ofSeconds(10);

    @Autowired
    CouponEventService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    AppClock clock;

    long storeA;
    long storeB;

    @BeforeEach
    void seed() {
        clock.setTo(NOW);
        long regionId = insert("INSERT INTO region (name) VALUES ('쿠폰 테스트 권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley) VALUES (" + regionId + ", 'A', 'cafe', 'center')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley) VALUES (" + regionId + ", 'B', 'dessert', 'center')");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void 처음에는_진행_중인_행사가_없다() {
        assertThat(service.current(storeA).current()).isNull();
    }

    @Test
    void 만들면_바로_확정되고_진행_중인_행사로_보인다() {
        CouponEventResponse created = service.create(storeA, percent(10, 5000, null));

        assertThat(created.storeId()).isEqualTo(String.valueOf(storeA));
        assertThat(created.benefitType()).isEqualTo(BenefitType.PERCENT_DISCOUNT);
        assertThat(created.discountPercent()).isEqualTo(10);
        assertThat(created.maxDiscountAmount()).isEqualTo(5000);
        assertThat(created.validDays()).isEqualTo(7);
        assertThat(created.totalQuantity()).isEqualTo(100);
        assertThat(created.issuedCount()).isZero();
        assertThat(created.remainingQuantity()).isEqualTo(100);
        assertThat(created.confirmedAt()).isBetween(NOW, NOW.plus(CLOCK_DRIFT));
        assertThat(created.stoppedAt()).isNull();
        assertThat(created.benefitAvailable()).isTrue();

        entityManager.clear();
        CouponEventResponse current = service.current(storeA).current();
        assertThat(current).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT discount_rate FROM coupon_policy WHERE id = ?", Integer.class,
                Long.parseLong(created.id()))).isEqualTo(10);
    }

    @Test
    void 진행_중인_행사가_있으면_하나_더_만들_수_없다() {
        service.create(storeA, percent(10, null, null));

        assertError(ErrorCode.CONFLICT, () -> service.create(storeA, percent(20, null, null)));
        assertThat(count(storeA)).isEqualTo(1);
    }

    @Test
    void 다른_가게의_행사는_보이지_않고_서로_막지_않는다() {
        service.create(storeA, percent(10, null, null));

        assertThat(service.current(storeB).current()).isNull();
        service.create(storeB, percent(20, null, null));
        assertThat(service.current(storeB).current().discountPercent()).isEqualTo(20);
    }

    @Test
    void 중단하면_진행_중인_행사가_없어지고_새로_만들_수_있다() {
        CouponEventResponse first = service.create(storeA, percent(10, null, 3));
        clock.setTo(NOW.plusSeconds(60));

        CouponEventResponse stopped = service.stop(storeA, Long.parseLong(first.id()));

        assertThat(stopped.stoppedAt()).isBetween(NOW.plusSeconds(60), NOW.plusSeconds(60).plus(CLOCK_DRIFT));
        assertThat(stopped.benefitAvailable()).isFalse();
        assertThat(stopped.validDays()).isEqualTo(3);
        assertThat(service.current(storeA).current()).isNull();

        CouponEventResponse second = service.create(storeA, percent(20, null, 14));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(service.current(storeA).current().id()).isEqualTo(second.id());
        assertThat(count(storeA)).isEqualTo(2);
    }

    @Test
    void 이미_중단한_행사는_다시_중단할_수_없다() {
        long id = Long.parseLong(service.create(storeA, percent(10, null, null)).id());
        service.stop(storeA, id);

        assertError(ErrorCode.CONFLICT, () -> service.stop(storeA, id));
    }

    @Test
    void 다른_가게_행사나_없는_행사는_404() {
        long id = Long.parseLong(service.create(storeA, percent(10, null, null)).id());

        assertError(ErrorCode.NOT_FOUND, () -> service.stop(storeB, id));
        assertError(ErrorCode.NOT_FOUND, () -> service.stop(storeA, id + 1000));
        entityManager.clear();
        assertThat(service.current(storeA).current().stoppedAt()).isNull();
    }

    @Test
    void 총수량이_다_나가도_중단_전까지는_진행_중이고_예약_혜택은_내려간다() {
        long id = Long.parseLong(service.create(storeA, percent(10, null, null)).id());
        jdbc.update("UPDATE coupon_policy SET issued_count = total_quantity WHERE id = ?", id);
        entityManager.clear();

        CouponEventResponse current = service.current(storeA).current();
        assertThat(current.remainingQuantity()).isZero();
        assertThat(current.benefitAvailable()).isFalse();
        assertError(ErrorCode.CONFLICT, () -> service.create(storeA, percent(20, null, null)));
    }

    @Test
    void 검사에_걸리면_아무것도_저장하지_않는다() {
        assertError(ErrorCode.INVALID, () -> service.create(storeA, percent(51, null, null)));
        assertThat(count(storeA)).isZero();
    }

    @Test
    void 서비스_제공_행사를_저장한다() {
        CouponEventResponse created = service.create(storeA, new CouponEventCreateRequest(
                "service", null, null, null, "아메리카노 1잔", 4000, 30, "2인 이상", 50));

        assertThat(created.benefitType()).isEqualTo(BenefitType.SERVICE);
        assertThat(created.serviceName()).isEqualTo("아메리카노 1잔");
        assertThat(created.serviceValue()).isEqualTo(4000);
        assertThat(created.discountAmount()).isNull();
        assertThat(created.validDays()).isEqualTo(30);
        assertThat(created.conditions()).isEqualTo("2인 이상");
    }

    private static CouponEventCreateRequest percent(Integer percent, Integer max, Integer validDays) {
        return new CouponEventCreateRequest("percent_discount", null, percent, max, null, null, validDays, null, 100);
    }

    private int count(long storeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon_policy WHERE store_id = ?", Integer.class, storeId);
    }

    private static void assertError(ErrorCode code, Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }

    private long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }
}
