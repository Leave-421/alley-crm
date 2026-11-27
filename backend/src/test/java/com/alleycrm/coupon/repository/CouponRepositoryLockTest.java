package com.alleycrm.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

/**
 * 쿠폰 조회의 행 잠금이 맞는 메서드에 붙어 있다. 잠그는 조회는 이름에 {@code WithLock}을 넣고, 그 밖의 조회는 잠그지 않는다.
 * 읽기 전용 트랜잭션에서 잠그는 조회를 부르면 500이 나고, 잠금이 빠지면 같은 쿠폰으로 동시에 신청할 수 있다(#261 회귀). DB 없이 확인한다.
 */
class CouponRepositoryLockTest {

    @Test
    void 이름에_WithLock이_있는_조회만_비관적_쓰기_잠금을_건다() {
        List<Method> methods = Arrays.stream(CouponRepository.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isDefault())
                .toList();

        assertThat(methods).filteredOn(m -> m.getName().contains("WithLock"))
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.getAnnotation(Lock.class))
                        .as(m.getName()).isNotNull()
                        .extracting(Lock::value).isEqualTo(LockModeType.PESSIMISTIC_WRITE));
        assertThat(methods).filteredOn(m -> !m.getName().contains("WithLock"))
                .allSatisfy(m -> assertThat(m.getAnnotation(Lock.class)).as(m.getName()).isNull());
    }

    @Test
    void 예약_신청의_쿠폰_조회는_잠그고_예약_화면_목록은_잠그지_않는다() throws Exception {
        assertThat(CouponRepository.class.getMethod("findWithLockByIdAndCustomerId", Long.class, Long.class)
                .getAnnotation(Lock.class)).isNotNull();
        assertThat(Arrays.stream(CouponRepository.class.getMethods())
                .filter(m -> m.getName().equals("findByCustomerIdAndIssuerKindAndStatusAndExpiresAtAfter"))
                .findFirst().orElseThrow().getAnnotation(Lock.class)).isNull();
    }
}
