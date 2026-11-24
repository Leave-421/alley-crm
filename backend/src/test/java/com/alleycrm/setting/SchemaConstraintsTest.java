package com.alleycrm.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** D19(#120) 스키마의 규칙성 제약. 기능 로직은 각 도메인 이슈에서 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTest {

    @Autowired
    JdbcTemplate jdbc;

    long regionId;
    long storeA;
    long storeB;
    long customerId;

    @BeforeEach
    void seed() {
        regionId = insert("INSERT INTO region (name) VALUES ('테스트 권역')");
        storeA = insert("INSERT INTO store (region_id, name, category, alley) VALUES (" + regionId + ", 'A', 'restaurant', 'front_gate')");
        storeB = insert("INSERT INTO store (region_id, name, category, alley) VALUES (" + regionId + ", 'B', 'cafe', 'center')");
        customerId = insert("INSERT INTO customer (type, name, phone) VALUES ('guest', '손님', '01000000000')");
    }

    @Test
    void 정식_코스_제안은_기준_예약과_추천_가게_조합당_1건() {
        long base = reservation("R-1", storeA);
        jdbc.update("INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, NOW(6))", base, storeB);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at) VALUES (?, ?, NOW(6))", base, storeB))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 소개_수수료는_예약당_한_번() {
        long r = reservation("R-2", storeB);
        String sql = """
                INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount, fee_base, fee_rate, fee_amount, settlement_month)
                VALUES (?, ?, ?, 10000, 10000, 0.04, 400, '2026-11')
                """;
        jdbc.update(sql, r, storeB, storeA);

        assertThatThrownBy(() -> jdbc.update(sql, r, storeB, storeA)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void 쿠폰_총수량을_넘는_발급_수는_막힌다() {
        jdbc.update("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity, issued_count) VALUES (?, 'fixed_discount', 1000, 1, 1)", storeA);

        // 조건부 UPDATE(issued < total일 때만 +1)는 0행을 바꾼다
        int changed = jdbc.update("UPDATE coupon_policy SET issued_count = issued_count + 1 WHERE store_id = ? AND issued_count < total_quantity", storeA);
        assertThat(changed).isZero();
        // 조건 없이 늘려도 CHECK가 막는다
        assertThatThrownBy(() -> jdbc.update("UPDATE coupon_policy SET issued_count = issued_count + 1 WHERE store_id = ?", storeA))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_coupon_policy_quantity");
    }

    @Test
    void 지갑은_주인당_하나() {
        jdbc.update("INSERT INTO wallet (owner_type, owner_id) VALUES ('store', ?)", storeA);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO wallet (owner_type, owner_id) VALUES ('store', ?)", storeA))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 공유_정책은_끌_수_있는_항목만_행으로_둔다() {
        jdbc.update("INSERT INTO share_policy (region_id, item, enabled) VALUES (?, 'gender_age_group', FALSE)", regionId);

        // 이름 · 연락처 · 방문한 가게 목록은 끌 수 없다(11-24 (5))
        assertThatThrownBy(() -> jdbc.update("INSERT INTO share_policy (region_id, item, enabled) VALUES (?, 'visited_stores', FALSE)", regionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_share_policy_item");
    }

    @Test
    void 월_정산은_권역_달마다_하나() {
        jdbc.update("INSERT INTO settlement (region_id, month) VALUES (?, '2026-11')", regionId);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO settlement (region_id, month) VALUES (?, '2026-11')", regionId))
                .isInstanceOf(DataAccessException.class);
    }

    private long reservation(String no, long storeId) {
        return insert("INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at) VALUES ('"
                + no + "', " + storeId + ", " + customerId + ", NOW(6), 2, NOW(6))");
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
