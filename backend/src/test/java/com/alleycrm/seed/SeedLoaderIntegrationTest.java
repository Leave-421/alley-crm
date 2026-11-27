package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.time.AppClock;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 시드 초기화 · 적재를 실제 MySQL 8.4(Testcontainers)에서 확인한다. Docker 필요.
 * 기준: NFR-07 #1 · #3, 06-data STORE, 11-24 (5), 00-backlog 「같이 쓰는 것 규칙」 시드 · 시연 데이터.
 * NFR-07 #2(예약 날짜 9 ~ 11월)는 가상 데이터 1(E16)을 넣은 뒤 확인한다. 가상 손님 수는 테스트를 빠르게 하려고 줄인다.
 * 가상 데이터 기간은 서버 시계로 정하므로(#315) 시계를 2026-12-01 14:00 KST로 고정한다(지난 기록 9/1 ~ 11/30).
 */
// 루트 .env에 시연 비밀번호가 있어도 이 테스트는 시연 계정 없이 돈다(계정 수 확인).
@SpringBootTest(properties = {"seed.close-after-run=false", "seed.demo.customers=80",
        "DEMO_CUSTOMER_PASSWORD=", "DEMO_OWNER_PASSWORD=", "DEMO_ASSOCIATION_PASSWORD="})
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedLoaderIntegrationTest {

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    AppClock clock;

    /** 시드를 돌린 시각: 2026-12-01 14:00 KST. */
    static final Instant NOW = Instant.parse("2026-12-01T05:00:00Z");

    private final SeedRegionData data = SeedDataLoader.loadRegionStores();

    @BeforeEach
    void fixClock() {
        clock.setTo(NOW);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void NFR_07_1_적재하면_권역_1개와_참여_가게_15곳_업종_혼합() {
        loader.resetAndLoad(data);

        assertThat(count("region")).isEqualTo(1);
        assertThat(count("store")).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT category) FROM store", Integer.class)).isEqualTo(8);
        List<Map<String, Object>> perAlley = jdbc.queryForList("SELECT alley, COUNT(*) AS n FROM store GROUP BY alley");
        assertThat(perAlley).hasSize(3).allSatisfy(row -> assertThat(((Number) row.get("n")).intValue()).isEqualTo(5));
    }

    @Test
    void 시드_가게는_승인_영업_예약_받는_중이고_실제_좌표는_비어_있다() {
        loader.resetAndLoad(data);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM store
                WHERE join_status = 'approved' AND business_status = 'open' AND reservation_enabled = TRUE
                  AND real_verified = FALSE AND real_lat IS NULL AND real_lng IS NULL
                  AND open_time IS NOT NULL AND close_time IS NOT NULL AND address IS NOT NULL
                  AND map_x IS NOT NULL AND map_y IS NOT NULL
                """, Integer.class)).isEqualTo(15);
    }

    @Test
    void 시드_파일의_값이_칸에_그대로_들어간다() {
        loader.resetAndLoad(data);
        SeedRegionData.Store workshop = data.stores().stream().filter(s -> s.code().equals("S13")).findFirst().orElseThrow();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, alley, address, closed_days, max_party_size, cancel_deadline_hours, "
                        + "TIME_FORMAT(open_time, '%H:%i') AS open_hm, TIME_FORMAT(close_time, '%H:%i') AS close_hm "
                        + "FROM store WHERE name = ?", workshop.name());

        assertThat(row.get("category")).isEqualTo("workshop");
        assertThat(row.get("alley")).isEqualTo("back_street");
        assertThat(row.get("address")).isEqualTo(workshop.address());
        assertThat(row.get("closed_days")).isEqualTo("mon,tue");
        assertThat(((Number) row.get("max_party_size")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("cancel_deadline_hours")).intValue()).isEqualTo(24);
        assertThat(row.get("open_hm")).isEqualTo("13:00");
        assertThat(row.get("close_hm")).isEqualTo("21:00");
    }

    @Test
    void 두_번_돌려도_결과가_같다() {
        loader.resetAndLoad(data);
        SeedLoader.Result second = loader.resetAndLoad(data);

        assertThat(count("region")).isEqualTo(1);
        assertThat(count("store")).isEqualTo(15);
        assertThat(second.regionStores().storeIdsByCode()).hasSize(15).containsKeys("S01", "S15");
    }

    @Test
    void 다른_표가_가게를_가리켜도_참조_무결성을_지키며_모두_지운다() {
        SeedLoader.Result first = loader.resetAndLoad(data);
        long regionId = first.regionStores().regionId();
        long storeId = first.regionStores().storeIdsByCode().get("S01");
        jdbc.update("INSERT INTO account (login_id, password_hash, role, store_id) VALUES ('owner-s01', 'x', 'owner', ?)", storeId);
        long accountId = jdbc.queryForObject("SELECT id FROM account WHERE login_id = 'owner-s01'", Long.class);
        jdbc.update("INSERT INTO customer (type, account_id) VALUES ('member', ?)", accountId);
        long customerId = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        jdbc.update("INSERT INTO visitor (anon_id, customer_id) VALUES ('00000000-0000-0000-0000-000000000001', ?)", customerId);
        jdbc.update("INSERT INTO setting (region_id, setting_key, value) VALUES (?, 'revisit_days', '90')", regionId);

        SeedLoader.Result second = loader.resetAndLoad(data);

        assertThat(count("account")).isZero();
        assertThat(countReal("customer")).isZero();
        // 시드도 가상 방문자(E17)를 넣으므로, 직접 넣은 방문자가 지워졌는지 본다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visitor WHERE anon_id = ?", Integer.class,
                "00000000-0000-0000-0000-000000000001")).isZero();
        assertThat(count("setting")).isZero();
        assertThat(count("store")).isEqualTo(15);
        assertThat(second.clearedTables()).doesNotContain("flyway_schema_history");
        assertThat(count("flyway_schema_history")).isPositive();
    }

    @Test
    void 다음_코스_예약의_자기_참조와_예약_정식_제안_순환이_있어도_지운다() {
        SeedLoader.Result first = loader.resetAndLoad(data);
        long storeA = first.regionStores().storeIdsByCode().get("S07");
        long storeB = first.regionStores().storeIdsByCode().get("S08");
        jdbc.update("INSERT INTO customer (type, name, phone) VALUES ('guest', '테스트', '010-0000-0000')");
        long customerId = jdbc.queryForObject("SELECT id FROM customer WHERE phone = '010-0000-0000'", Long.class);
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at)
                VALUES ('T-BASE', ?, ?, '2026-10-02 09:00:00', 2, '2026-10-01 09:00:00')""", storeA, customerId);
        long baseId = jdbc.queryForObject("SELECT id FROM reservation WHERE reservation_no = 'T-BASE'", Long.class);
        jdbc.update("""
                INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at)
                VALUES (?, ?, '2026-10-01 09:01:00')""", baseId, storeB);
        long proposalId = jdbc.queryForObject("SELECT id FROM course_proposal WHERE base_reservation_id = ?", Long.class, baseId);
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, requested_at,
                                         course_root_id, course_proposal_id, sender_store_id)
                VALUES ('T-NEXT', ?, ?, '2026-10-02 11:00:00', 2, '2026-10-01 09:02:00', ?, ?, ?)""",
                storeB, customerId, baseId, proposalId, storeA);

        loader.resetAndLoad(data);

        assertThat(countReal("reservation")).isZero();
        assertThat(countReal("course_proposal")).isZero(); // 시드의 정식 제안(E17)은 data_source = demo
        assertThat(countReal("customer")).isZero();
        assertThat(count("store")).isEqualTo(15);
    }

    @Test
    void 중간에_실패하면_지운_것까지_롤백된다() {
        SeedLoader.Result before = loader.resetAndLoad(data);
        // 검사기를 거치지 않으면 DB 제약(취소 마감 0 ~ 24)에 걸리는 가게를 마지막에 둔다.
        List<SeedRegionData.Store> stores = new ArrayList<>(data.stores());
        SeedRegionData.Store last = stores.getLast();
        stores.set(stores.size() - 1, new SeedRegionData.Store(last.code(), last.name(), last.category(), last.alley(),
                last.mapX(), last.mapY(), last.address(), last.openTime(), last.closeTime(), last.closedDays(),
                last.slotMinutes(), last.capacityPerSlot(), last.bookingWindowDays(), 25, last.depositPerPerson(),
                last.maxPartySize()));
        SeedRegionData broken = new SeedRegionData(data.regionName(), data.plane(), stores);

        assertThatThrownBy(() -> loader.resetAndLoad(broken)).isInstanceOf(RuntimeException.class);

        assertThat(count("store")).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT id FROM region", Long.class)).isEqualTo(before.regionStores().regionId());
        // 실패 뒤에도 잠금이 풀려 다시 돌릴 수 있다.
        assertThat(loader.resetAndLoad(data).regionStores().storeIdsByCode()).hasSize(15);
    }

    @Test
    void 다른_실행이_잠금을_잡고_있으면_바로_실패하고_아무것도_지우지_않는다() throws Exception {
        loader.resetAndLoad(data);
        try (Connection other = dataSource.getConnection(); Statement st = other.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT GET_LOCK('" + SeedLoader.LOCK_NAME + "', 0)")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            assertThatThrownBy(() -> loader.resetAndLoad(data))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("진행 중");
            assertThat(count("store")).isEqualTo(15);

            st.executeQuery("SELECT RELEASE_LOCK('" + SeedLoader.LOCK_NAME + "')").close();
        }
    }

    @Test
    void 가상_데이터_1은_시연_데이터로_9월에서_11월_예약과_취소_방문을_넣는다_NFR_07_2() {
        SeedLoader.Result result = loader.resetAndLoad(data);

        assertThat(result.demoReservations().customers()).isEqualTo(80);
        assertThat(count("customer")).isEqualTo(80);
        assertThat(count("reservation"))
                .isEqualTo(result.demoReservations().reservations() + result.demoUpcoming().reservations()).isPositive();
        assertThat(countReal("customer")).isZero();
        assertThat(countReal("reservation")).isZero();
        // 시각 칸은 UTC. 운영 시간대(KST)로 바꿔 지난 기록(방문 · 취소)이 학기 중 9 ~ 11월(어제까지)인지 본다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE status IN ('visited', 'canceled')
                  AND DATE(DATE_ADD(start_at, INTERVAL 9 HOUR)) NOT BETWEEN '2026-09-01' AND '2026-11-30'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE status = 'visited' AND (visit_amount IS NULL OR visited_at IS NULL)",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE status = 'canceled' AND (cancel_reason IS NULL OR canceled_at IS NULL)",
                Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM reservation", String.class))
                .containsExactlyInAnyOrder("visited", "canceled", "requested", "confirmed");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation r JOIN customer c ON c.id = r.customer_id
                WHERE r.status = 'visited' AND (c.last_visit_at IS NULL OR c.last_visit_at < r.visited_at)
                """, Integer.class)).isZero();
    }

    @Test
    void 다가올_예약과_방문_신고는_시드를_돌린_시각_기준이다_315() {
        SeedLoader.Result result = loader.resetAndLoad(data);
        DemoUpcomingSeeder.Result upcoming = result.demoUpcoming();

        assertThat(result.period().today()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(upcoming.reservations()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE status IN ('requested', 'confirmed')",
                Integer.class)).isEqualTo(upcoming.reservations());
        // 다가올 예약은 지금 뒤, 신청 · 확정은 지금 전. 지난 기록은 지금 뒤에 방문 · 취소 · 시작이 없다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE status IN ('requested', 'confirmed')
                  AND (start_at <= ? OR requested_at >= ? OR confirmed_at >= ? OR pending_start_at <= ?)
                """, Integer.class, utc(NOW), utc(NOW), utc(NOW), utc(NOW))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE status IN ('visited', 'canceled') AND (start_at >= ? OR visited_at >= ? OR canceled_at >= ?)
                """, Integer.class, utc(NOW), utc(NOW), utc(NOW))).isZero();
        // 변경 신청은 상태 신청 · 확정 시각 있음 · 바꿀 시각과 인원이 같이 있다(D8)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation
                WHERE pending_start_at IS NOT NULL
                  AND (status <> 'requested' OR confirmed_at IS NULL OR pending_party_size IS NULL)
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE pending_start_at IS NOT NULL", Integer.class))
                .isEqualTo(upcoming.changeRequests());
        // 지연 입력은 확정 예약에만
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE delay_eta IS NOT NULL AND status <> 'confirmed'",
                Integer.class)).isZero();
        // 방문 신고는 방문 처리된 회원 예약에, 방문 처리 뒤 · 지금 전
        assertThat(count("visit_report")).isEqualTo(upcoming.reports());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM visit_report v
                JOIN reservation r ON r.id = v.reservation_id JOIN customer c ON c.id = v.customer_id
                WHERE r.status <> 'visited' OR r.customer_id <> v.customer_id OR c.type <> 'member'
                   OR v.created_at <= r.visited_at OR v.created_at >= ?
                """, Integer.class, utc(NOW))).isZero();
        // 다가올 예약은 소개 · 쿠폰 · 예약금 거래를 만들지 않는다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation r
                WHERE r.status IN ('requested', 'confirmed')
                  AND (r.sender_store_id IS NOT NULL OR r.course_root_id IS NOT NULL
                       OR EXISTS (SELECT 1 FROM coupon c WHERE c.reservation_id = r.id)
                       OR EXISTS (SELECT 1 FROM wallet_tx t WHERE t.reservation_id = r.id))
                """, Integer.class)).isZero();
    }

    @Test
    void 가상_데이터_2는_노출_코스_제안_소개_수수료_정산을_같은_원천에서_맞게_넣는다_NFR_08_4() {
        SeedLoader.Result result = loader.resetAndLoad(data);
        DemoCourseSeeder.Result course = result.demoCourses();

        assertThat(count("visitor")).isEqualTo(course.visitors()).isPositive();
        // 노출 = 지도 추천 노출(E17b) + 쿠폰 출처 노출(쿠폰 한 장에 하나) + 중복 차단 노출(쿠폰 사례)
        DemoCouponSeeder.Result coupons = result.demoCoupons();
        assertThat(count("recommendation_exposure"))
                .isEqualTo(course.exposures() + coupons.coupons() + coupons.blockedRevisits()).isPositive();
        assertThat(count("course_proposal")).isEqualTo(course.proposals());
        assertThat(countReal("recommendation_exposure")).isZero();
        assertThat(countReal("course_proposal")).isZero();
        assertThat(countReal("referral_fee")).isZero();
        // 제안은 기준 예약 신청 뒤에 표시했다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_proposal p JOIN reservation b ON b.id = p.base_reservation_id
                WHERE p.first_shown_at <= b.requested_at
                """, Integer.class)).isZero();
        // 소개 후보는 기준 예약 · 제안 · 보낸 가게가 함께 있고, 제안은 그 기준 예약 · 그 가게 것(D12)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation r
                LEFT JOIN course_proposal p ON p.id = r.course_proposal_id
                LEFT JOIN reservation b ON b.id = r.course_root_id
                WHERE r.course_proposal_id IS NOT NULL
                  AND (r.sender_store_id IS NULL OR p.base_reservation_id <> r.course_root_id
                       OR p.recommended_store_id <> r.store_id OR b.store_id <> r.sender_store_id
                       OR r.requested_at <= p.first_shown_at)
                """, Integer.class)).isZero();
        // 수수료는 방문 처리된 소개 예약마다 한 번, 원천 기록과 같은 값(NFR-08 #4)
        assertThat(count("referral_fee")).isEqualTo(course.referralFees()).isEqualTo(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE status = 'visited' AND sender_store_id IS NOT NULL", Integer.class));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM referral_fee f JOIN reservation r ON r.id = f.reservation_id
                WHERE f.visit_amount <> r.visit_amount OR f.fee_amount <> FLOOR(r.visit_amount * f.fee_rate)
                   OR f.receiving_store_id <> r.store_id OR f.sender_store_id <> r.sender_store_id
                """, Integer.class)).isZero();
        long totalFee = jdbc.queryForObject("SELECT COALESCE(SUM(fee_amount), 0) FROM referral_fee", Long.class);
        assertThat(totalFee).isEqualTo(course.totalFee());
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM wallet WHERE owner_type = 'fund'", Long.class))
                .isEqualTo(totalFee);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM wallet WHERE owner_type = 'store'", Long.class))
                .isEqualTo(-totalFee);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet w
                WHERE w.balance <> (SELECT COALESCE(SUM(t.amount), 0) FROM wallet_tx t WHERE t.wallet_id = w.id)
                """, Integer.class)).isZero();
        // 10월까지는 정산 확정, 11월은 집계 중(행 없음)
        assertThat(count("settlement")).isEqualTo(course.confirmedSettlements());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE status <> 'confirmed' OR month > '2026-10'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM referral_fee
                WHERE (settlement_month <= '2026-10') <> (settlement_id IS NOT NULL)
                """, Integer.class)).isZero();
    }

    @Test
    void 쿠폰_시연_사례를_같은_원천에_맞게_넣는다_NFR_08_5() {
        SeedLoader.Result result = loader.resetAndLoad(data);
        DemoCouponSeeder.Result coupon = result.demoCoupons();

        assertThat(count("coupon_policy")).isEqualTo(coupon.events()).isPositive();
        assertThat(count("coupon")).isEqualTo(coupon.coupons());
        assertThat(countReal("coupon")).isZero();
        // 행사의 발급 수 = 그 행사 쿠폰 수
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon_policy p
                WHERE p.issued_count <> (SELECT COUNT(*) FROM coupon c WHERE c.coupon_policy_id = p.id)
                """, Integer.class)).isZero();
        // 출처 노출은 그 가게를 추천한 이 손님의 노출이고, 받기 전에 보였다(FR-CPN-10)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon c
                JOIN recommendation_exposure e ON e.id = c.source_exposure_id
                JOIN visitor v ON v.id = e.visitor_id
                WHERE e.recommended_store_id <> c.target_store_id OR e.base_store_id <> c.source_base_store_id
                   OR v.customer_id <> c.customer_id OR e.shown_at >= c.downloaded_at
                """, Integer.class)).isZero();
        // 적용 · 사용 완료 · 적용 취소는 그 가게 그 손님 예약에, 신청 시각이 기한 안(FR-CPN-05)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon c JOIN reservation r ON r.id = c.reservation_id
                WHERE r.store_id <> c.target_store_id OR r.customer_id <> c.customer_id
                   OR r.requested_at <= c.downloaded_at OR r.requested_at >= c.expires_at
                   OR (c.status IN ('applied', 'used') AND r.status <> 'visited')
                   OR (c.status = 'apply_canceled' AND r.status <> 'canceled')
                """, Integer.class)).isZero();
        // 소개 예약에는 쿠폰이 없어 수수료 기록(E17b)의 쿠폰 할인 0과 맞다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM coupon c JOIN reservation r ON r.id = c.reservation_id WHERE r.sender_store_id IS NOT NULL
                """, Integer.class)).isZero();
        // 사례가 모두 있는지는 실제 시드 크기(손님 600명)로 DemoCouponPlannerTest가 본다. 여기(80명)는 값만 본다
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM coupon", String.class))
                .isNotEmpty().isSubsetOf("used", "applied", "apply_canceled", "expired", "downloaded");
    }

    @Test
    void 공공데이터_기본값을_출처_기준_기간과_함께_넣는다_NFR_10() {
        SeedLoader.Result result = loader.resetAndLoad(data);

        assertThat(result.publicStats()).isEqualTo(PublicStatFile.load().size());
        assertThat(count("public_stat")).isEqualTo(result.publicStats());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM public_stat WHERE TRIM(source) = '' OR TRIM(period) = ''", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT kind FROM public_stat", String.class))
                .containsExactlyInAnyOrder("attractiveness", "clq", "day_share", "time_band_share", "gender_share", "age_share");

        loader.resetAndLoad(data);
        assertThat(count("public_stat")).isEqualTo(result.publicStats()); // 다시 넣어도 쌓이지 않는다
    }

    /** 시드 · 시연 데이터가 아닌 행(테스트가 직접 넣은 것) 수. */
    private int countReal(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE data_source <> 'demo'", Integer.class);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
