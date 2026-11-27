package com.alleycrm.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.account.AccountService;
import com.alleycrm.common.scheduler.ScheduledJobRunner;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** C12 비회원 파기 자동 작업 `guest_purge` (FR-CUS-07, NFR-06). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GuestPurgeJobTest {

    /** 2026-11-28 10:00 Asia/Seoul */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 1, 0);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppClock clock;
    @Autowired
    GuestPurgeJob job;
    @Autowired
    ScheduledJobRunner runner;
    @Autowired
    SettingService settings;
    @Autowired
    AccountService accountService;

    long regionId;
    long storeId;
    final List<Long> customers = new ArrayList<>();
    int seq;

    @BeforeEach
    void seed() {
        clock.setTo(NOW.toInstant(ZoneOffset.UTC));
        jdbc.update("INSERT INTO region (name) VALUES ('파기 권역')");
        regionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time, capacity_per_slot)
                VALUES (?, '파기 가게', 'cafe', 'center', 'approved', '10:00', '22:00', 5)
                """, regionId);
        storeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM customer_card WHERE store_id = ?", storeId);
        for (long c : customers) {
            jdbc.update("DELETE FROM customer WHERE id = ?", c);
        }
        jdbc.update("DELETE FROM account WHERE login_id LIKE 'gp%'");
        jdbc.update("DELETE FROM setting WHERE region_id = ?", regionId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        jdbc.update("DELETE FROM region WHERE id = ?", regionId);
    }

    @Test
    void FR_CUS_07_1_마지막_방문_후_6개월이_지난_비회원은_식별_정보만_지운다() {
        long guest = guest();
        reservation(guest, "visited", NOW.minusMonths(6).minusDays(1), true);
        jdbc.update("INSERT INTO customer_card (store_id, customer_id, memo) VALUES (?, ?, '창가 자리')", storeId, guest);

        assertThat(runner.runAll()).containsKey("guest_purge");

        Map<String, Object> c = jdbc.queryForMap("SELECT name, phone, gender, age_group, purged_at FROM customer WHERE id = ?", guest);
        assertThat(c.get("name")).isNull();
        assertThat(c.get("phone")).isNull();
        assertThat(c.get("purged_at")).isNotNull();
        // 비식별 집계는 남는다
        assertThat(c.get("gender")).isEqualTo("female");
        assertThat(c.get("age_group")).isEqualTo("age_30s");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE customer_id = ? AND status = 'visited'", Integer.class, guest)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT memo FROM customer_card WHERE customer_id = ?", String.class, guest)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE customer_id = ? AND delay_eta IS NOT NULL", Integer.class, guest)).isZero();
        // 다시 돌아도 같다
        assertThat(job.run()).isZero();
    }

    @Test
    void FR_CUS_07_2_마지막_방문_후_5개월이면_그대로() {
        long guest = guest();
        reservation(guest, "visited", NOW.minusMonths(8), false);
        reservation(guest, "visited", NOW.minusMonths(5), true); // 마지막 방문이 기준

        job.run();

        assertKept(guest);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE customer_id = ? AND delay_eta IS NOT NULL", Integer.class, guest)).isEqualTo(1);
    }

    @Test
    void FR_CUS_07_3_방문_기록_없는_비회원은_마지막_취소_후_30일이_지나면_지운다() {
        long due = guest();
        reservation(due, "canceled", NOW.minusDays(31), false);
        long notYet = guest();
        reservation(notYet, "canceled", NOW.minusDays(40), false);
        reservation(notYet, "canceled", NOW.minusDays(29), false); // 마지막 취소가 기준

        job.run();

        assertPurged(due);
        assertKept(notYet);
    }

    @Test
    void FR_CUS_07_4_회원은_1년이_지나도_그대로() {
        long accountId = accountService.signupCustomer("gpmember" + System.nanoTime(), "password1", "회원", null, null).id();
        long member = jdbc.queryForObject("SELECT id FROM customer WHERE account_id = ?", Long.class, accountId);
        customers.add(member);
        reservation(member, "visited", NOW.minusYears(1), true);

        job.run();

        assertThat(jdbc.queryForMap("SELECT name, purged_at FROM customer WHERE id = ?", member))
                .containsEntry("name", "회원").containsEntry("purged_at", null);
    }

    @Test
    void 신청_확정_예약이_남은_비회원은_건너뛴다() {
        long guest = guest();
        reservation(guest, "visited", NOW.minusMonths(7), false);
        reservation(guest, "confirmed", NOW.plusDays(1), false);

        job.run();

        assertKept(guest);
    }

    @Test
    void 보관_기간은_권역_설정값을_따른다() {
        settings.set(regionId, SettingKey.GUEST_RETENTION_MONTHS, "3");
        long guest = guest();
        reservation(guest, "visited", NOW.minusMonths(4), false);

        job.run();

        assertPurged(guest);
    }

    private void assertPurged(long customerId) {
        assertThat(jdbc.queryForMap("SELECT name, phone, purged_at FROM customer WHERE id = ?", customerId))
                .containsEntry("name", null).containsEntry("phone", null).extractingByKey("purged_at").isNotNull();
    }

    private void assertKept(long customerId) {
        Map<String, Object> c = jdbc.queryForMap("SELECT name, phone, purged_at FROM customer WHERE id = ?", customerId);
        assertThat(c.get("name")).isEqualTo("파기손님");
        assertThat(c.get("phone")).isNotNull();
        assertThat(c.get("purged_at")).isNull();
    }

    private long guest() {
        jdbc.update("INSERT INTO customer (type, name, phone, gender, age_group) VALUES ('guest', '파기손님', ?, 'female', 'age_30s')",
                "010" + (System.nanoTime() % 100_000_000L));
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        customers.add(id);
        return id;
    }

    /** at = 방문 처리 · 취소 시각(예약 시각도 같게 둔다) */
    private void reservation(long customerId, String status, LocalDateTime at, boolean withDelay) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, cancel_reason,
                                         requested_at, visited_at, canceled_at, delay_eta)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, ?, ?, ?)
                """, "P" + System.nanoTime() % 1_000_000_000L + seq++, storeId, customerId, at, status,
                "canceled".equals(status) ? "customer_canceled" : null, at.minusDays(1),
                "visited".equals(status) ? at : null, "canceled".equals(status) ? at : null,
                withDelay ? at.plusMinutes(10) : null);
    }
}
