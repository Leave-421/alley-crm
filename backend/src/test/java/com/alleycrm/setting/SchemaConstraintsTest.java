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
    void 진행_중인_쿠폰_행사는_가게당_하나_중단하면_새_행사_가능() {
        String sql = "INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity) VALUES (?, 'fixed_discount', 1000, 10)";
        long first = insert(sql.replace("?", String.valueOf(storeA)));

        assertThatThrownBy(() -> jdbc.update(sql, storeA))
                .isInstanceOf(DataAccessException.class);

        // 중단하면(11-25) 같은 가게에 새 행사를 만들 수 있다
        jdbc.update("UPDATE coupon_policy SET stopped_at = NOW(6) WHERE id = ?", first);
        jdbc.update(sql, storeA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_policy WHERE store_id = ?", Integer.class, storeA)).isEqualTo(2);
    }

    @Test
    void 쿠폰_기간은_1에서_30일_정률은_1에서_50퍼센트() {
        String sql = "INSERT INTO coupon_policy (store_id, benefit_type, discount_rate, valid_days, total_quantity) VALUES (?, 'percent_discount', ?, ?, 10)";

        assertThatThrownBy(() -> jdbc.update(sql, storeA, 10, 31))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_coupon_policy_valid_days");
        assertThatThrownBy(() -> jdbc.update(sql, storeA, 51, 7))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_coupon_policy_rate");
        jdbc.update(sql, storeA, 50, 30);
    }

    @Test
    void 예약_가능_기간은_기본_30일_1에서_60일() {
        assertThat(jdbc.queryForObject("SELECT booking_window_days FROM store WHERE id = ?", Integer.class, storeA)).isEqualTo(30);

        assertThatThrownBy(() -> jdbc.update("UPDATE store SET booking_window_days = 61 WHERE id = ?", storeA))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_store_booking_window");
        jdbc.update("UPDATE store SET booking_window_days = 60 WHERE id = ?", storeA);
    }

    @Test
    void 상인회_쿠폰_정률은_최대_할인액이_필수() {
        String sql = """
                INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_rate, max_discount_amount, total_quantity,
                    eligible_store_scope, issue_start_at, issue_end_at, valid_start_at, valid_end_at, reserved_budget, confirmed_at)
                VALUES (?, '행사', 'percent_discount', 20, ?, 10, 'selected', NOW(6), NOW(6) + INTERVAL 1 DAY, NOW(6), NOW(6) + INTERVAL 7 DAY, 50000, NOW(6))
                """;
        assertThatThrownBy(() -> jdbc.update(sql, regionId, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_acoupon_event_benefit");
        jdbc.update(sql, regionId, 5000);
    }

    @Test
    void 상인회_쿠폰은_같은_행사_같은_회원_1장이고_가게_칸은_비운다() {
        long member = insert("INSERT INTO customer (type) VALUES ('member')");
        long event = insert("INSERT INTO association_coupon_event (region_id, title, benefit_type, discount_amount, total_quantity, eligible_store_scope,"
                + " issue_start_at, issue_end_at, valid_start_at, valid_end_at, reserved_budget, confirmed_at) VALUES (" + regionId
                + ", '행사', 'fixed_discount', 1000, 10, 'all', NOW(6), NOW(6) + INTERVAL 1 DAY, NOW(6), NOW(6) + INTERVAL 7 DAY, 10000, NOW(6))");
        String sql = "INSERT INTO coupon (customer_id, issuer_kind, association_event_id, downloaded_at, expires_at) VALUES (?, 'association', ?, NOW(6), NOW(6))";
        jdbc.update(sql, member, event);

        assertThatThrownBy(() -> jdbc.update(sql, member, event)).isInstanceOf(DataAccessException.class);
        // 상인회 쿠폰에 가게 쿠폰 행사를 같이 채우면 막힌다
        long policy = insert("INSERT INTO coupon_policy (store_id, benefit_type, discount_amount, total_quantity) VALUES (" + storeA + ", 'fixed_discount', 1000, 10)");
        long other = insert("INSERT INTO customer (type) VALUES ('member')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO coupon (customer_id, issuer_kind, association_event_id, coupon_policy_id, downloaded_at, expires_at) VALUES (?, 'association', ?, ?, NOW(6), NOW(6))",
                other, event, policy))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_coupon_issuer");
    }

    @Test
    void 방문_신고는_처리_완료일_때만_결과가_있고_신고당_정정은_한_번() {
        long member = insert("INSERT INTO customer (type) VALUES ('member')");
        long r = reservation("R-VR", storeA);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO visit_report (reservation_id, customer_id, type, status, result, created_at) VALUES (?, ?, 'amount', 'received', 'valid', NOW(6))", r, member))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_visit_report_result");
        long report = insert("INSERT INTO visit_report (reservation_id, customer_id, type, status, result, handled_at, created_at) VALUES ("
                + r + ", " + member + ", 'amount', 'done', 'valid', NOW(6), NOW(6))");
        String fix = "INSERT INTO visit_amount_correction (reservation_id, report_id, original_amount, corrected_amount, settlement_month, created_at) VALUES (?, ?, 30000, 40000, '2026-11', NOW(6))";
        jdbc.update(fix, r, report);

        assertThatThrownBy(() -> jdbc.update(fix, r, report)).isInstanceOf(DataAccessException.class);
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
    void 같은_표시_이벤트의_노출_재전송은_1건만() {
        long visitor = insert("INSERT INTO visitor (anon_id) VALUES ('00000000-0000-0000-0000-000000000001')");
        String sql = """
                INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, badge, shown_at)
                VALUES (?, ?, ?, ?, 1, 'visit_data', NOW(6))
                """;
        jdbc.update(sql, visitor, "11111111-1111-1111-1111-111111111111", storeA, storeB);

        assertThatThrownBy(() -> jdbc.update(sql, visitor, "11111111-1111-1111-1111-111111111111", storeA, storeB))
                .isInstanceOf(DataAccessException.class);
        // 다시 그린 카드 묶음(새 display_id)은 그대로 남는다
        jdbc.update(sql, visitor, "22222222-2222-2222-2222-222222222222", storeA, storeB);
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
