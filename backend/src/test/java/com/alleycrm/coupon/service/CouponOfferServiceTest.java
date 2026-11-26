package com.alleycrm.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.coupon.domain.DownloadStatus;
import com.alleycrm.coupon.dto.CouponOfferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인하지 않은 쿠폰 상세(FR-CPN-03 #1). 경로를 로그인 없이 여는 보안 설정(C, 3.4 K-1) 전이라 서비스로 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponOfferServiceTest {

    @Autowired
    CouponOfferService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 로그인하지_않으면_login_required이고_내_쿠폰은_비어_있다() {
        long region = insert("INSERT INTO region (name) VALUES ('쿠폰 상세 권역')");
        long store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", 'B', 'cafe', 'center', 'approved')");
        insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, valid_days, total_quantity, "
                + "issued_count, confirmed_at) VALUES (" + store + ", 'fixed_discount', 3000, 7, 10, 0, UTC_TIMESTAMP(6))");

        CouponOfferResponse offer = service.offer(store, null);

        assertThat(offer.downloadStatus()).isEqualTo(DownloadStatus.LOGIN_REQUIRED);
        assertThat(offer.event()).isNotNull();
        assertThat(offer.nextDownloadableAt()).isNull();
        assertThat(offer.myCoupons()).isEmpty();
    }

    @Test
    void 로그인하지_않아도_행사가_없으면_unavailable이_먼저다() {
        long region = insert("INSERT INTO region (name) VALUES ('쿠폰 상세 권역')");
        long store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", 'B', 'cafe', 'center', 'approved')");

        assertThat(service.offer(store, null).downloadStatus()).isEqualTo(DownloadStatus.UNAVAILABLE);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
