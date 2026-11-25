package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 시드 초기화 · 적재를 실제 MySQL 8.4(Testcontainers)에서 확인한다. Docker 필요.
 * 기준: NFR-07 #1 · #3, 06-data STORE, 11-24 (5), 00-backlog 「같이 쓰는 것 규칙」 시드 · 시연 데이터.
 * NFR-07 #2(예약 날짜 9 ~ 11월)는 예약을 만드는 E16에서 확인한다.
 */
@SpringBootTest
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedLoaderIntegrationTest {

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private final SeedRegionData data = SeedDataLoader.loadRegionStores();

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
        assertThat(count("customer")).isZero();
        assertThat(count("visitor")).isZero();
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

        assertThat(count("reservation")).isZero();
        assertThat(count("course_proposal")).isZero();
        assertThat(count("customer")).isZero();
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
