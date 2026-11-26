package com.alleycrm.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.coupon.repository.CouponPolicyRepository;
import com.alleycrm.coupon.repository.CouponRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버 JVM 시간대가 UTC가 아니어도(KST 로컬 · 시연 노트북) JPA로 쓰고 읽는 쿠폰 시각이 SQL로 쓰고 읽는 시각과 같다(Bug #194).
 * DB의 DATETIME 칸은 UTC 시각이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CouponTimeZoneTest {

    @Autowired
    CouponRepository coupons;

    @Autowired
    CouponPolicyRepository events;

    @Autowired
    JdbcTemplate jdbc;

    TimeZone original;
    long store;
    long customer;
    long exposure;
    CouponPolicy event;

    @BeforeEach
    void seoul() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

        long region = insert("INSERT INTO region (name) VALUES ('시간대 권역')");
        store = insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", 'B', 'cafe', 'center', 'approved')");
        customer = insert("INSERT INTO customer (type) VALUES ('member')");
        long visitor = insert("INSERT INTO visitor (anon_id) VALUES ('" + UUID.randomUUID() + "')");
        exposure = insert("INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, "
                + "recommended_store_id, `rank`, page, badge, shown_at) VALUES (" + visitor + ", '" + UUID.randomUUID()
                + "', " + store + ", " + store + ", 1, 0, 'visit_data', UTC_TIMESTAMP(6))");
        event = events.saveAndFlush(CouponPolicy.confirm(store, new CouponEventTerms(
                BenefitType.FIXED_DISCOUNT, 3000, null, null, null, null, 7, null, 10), at("2026-12-01T09:00:00Z")));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    void JPA로_저장한_시각은_DB에_UTC_그대로_들어간다() {
        Instant downloadedAt = at("2026-12-01T09:00:00Z");
        Coupon saved = coupons.saveAndFlush(Coupon.download(customer, event, store, exposure, downloadedAt));

        assertThat(jdbc.queryForObject("SELECT downloaded_at FROM coupon WHERE id = ?", LocalDateTime.class, saved.getId()))
                .isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM coupon WHERE id = ?", LocalDateTime.class, saved.getId()))
                .isEqualTo(LocalDateTime.of(2026, 12, 8, 9, 0));
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM coupon_policy WHERE id = ?", LocalDateTime.class,
                event.getId())).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
    }

    @Test
    void SQL로_넣은_UTC_시각을_JPA로_읽으면_같은_시각이다() {
        jdbc.update("""
                INSERT INTO coupon (customer_id, coupon_policy_id, target_store_id, downloaded_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'downloaded')
                """, customer, event.getId(), store, LocalDateTime.of(2026, 12, 1, 9, 0),
                LocalDateTime.of(2026, 12, 8, 9, 0));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Coupon loaded = coupons.findById(id).orElseThrow();

        assertThat(loaded.getDownloadedAt()).isEqualTo(at("2026-12-01T09:00:00Z"));
        assertThat(loaded.getExpiresAt()).isEqualTo(at("2026-12-08T09:00:00Z"));
    }

    @Test
    void 기한_판단은_시간대와_상관없이_UTC_시각으로_한다() {
        Coupon coupon = coupons.saveAndFlush(Coupon.download(customer, event, store, exposure, at("2026-12-01T09:00:00Z")));
        Instant expiresAt = coupon.getExpiresAt();

        assertThat(coupon.isUsableAt(expiresAt.minus(1, ChronoUnit.MINUTES))).isTrue();
        assertThat(coupon.isUsableAt(expiresAt)).isFalse();
        assertThat(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)).isEqualTo(LocalDateTime.of(2026, 12, 8, 9, 0));
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
