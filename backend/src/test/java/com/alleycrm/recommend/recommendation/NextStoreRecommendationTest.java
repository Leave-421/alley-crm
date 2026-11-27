package com.alleycrm.recommend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.common.TestCsrf;
import com.alleycrm.common.time.AppClock;
import com.alleycrm.recommend.NextStoreRecommendedEvent;
import com.alleycrm.reservation.request.ReservationRequestedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 다음 가게 추천 알림 이벤트(CONTRACTS_API 3.11, FR-CRS-02). 예약 커밋 뒤에 도는 리스너라 테스트 트랜잭션을 쓰지 않고,
 * 이 테스트가 만든 권역의 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class NextStoreRecommendationTest {

    /** 2026-12-01 10:00 Asia/Seoul */
    private static final Instant NOW = Instant.parse("2026-12-01T01:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppClock clock;

    @Autowired
    ApplicationEvents events;

    @Autowired
    NextStoreRecommendationListener listener;

    final List<Long> regions = new ArrayList<>();

    @BeforeEach
    void freeze() {
        clock.setTo(NOW);
    }

    @AfterEach
    void clean() {
        clock.reset();
        for (long region : regions) {
            jdbc.update("""
                    DELETE FROM wallet_tx WHERE reservation_id IN
                      (SELECT r.id FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?)
                    """, region);
            jdbc.update("""
                    DELETE FROM share_consent WHERE reservation_id IN
                      (SELECT r.id FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?)
                    """, region);
            List<Long> customers = jdbc.queryForList(
                    "SELECT r.customer_id FROM reservation r JOIN store s ON s.id = r.store_id WHERE s.region_id = ?",
                    Long.class, region);
            jdbc.update("DELETE FROM reservation WHERE store_id IN (SELECT id FROM store WHERE region_id = ?)", region);
            for (long customer : customers) {
                jdbc.update("DELETE FROM visitor WHERE customer_id = ?", customer);
                jdbc.update("DELETE FROM customer_card WHERE customer_id = ?", customer);
                jdbc.update("DELETE FROM customer WHERE id = ?", customer);
            }
            jdbc.update("DELETE FROM store WHERE region_id = ?", region);
            jdbc.update("DELETE FROM region WHERE id = ?", region);
        }
    }

    @Test
    void 예약_시각을_기준으로_후보가_있으면_다음_가게_추천_이벤트를_낸다_FR_CRS_02_2() {
        long region = region();
        long base = store(region, "기준 카페", "cafe");
        store(region, "구름 디저트", "dessert");
        long customer = insert("INSERT INTO customer (type) VALUES ('member')");
        long reservation = reservation(base, customer);

        listener.onRequested(new ReservationRequestedEvent(reservation, base, customer));

        assertThat(events.stream(NextStoreRecommendedEvent.class))
                .containsExactly(new NextStoreRecommendedEvent(reservation, base, customer));
    }

    @Test
    void 후보가_없으면_이벤트를_내지_않는다() {
        long region = region();
        long alone = store(region, "혼자 카페", "cafe");
        long customer = insert("INSERT INTO customer (type) VALUES ('member')");
        long reservation = reservation(alone, customer);

        listener.onRequested(new ReservationRequestedEvent(reservation, alone, customer));

        assertThat(events.stream(NextStoreRecommendedEvent.class)).isEmpty();
    }

    @Test
    void 계산할_수_없어도_예외를_밖으로_내지_않는다() {
        assertThatCode(() -> listener.onRequested(new ReservationRequestedEvent(999_999_999L, 1L, 1L)))
                .doesNotThrowAnyException();
        assertThat(events.stream(NextStoreRecommendedEvent.class)).isEmpty();
    }

    @Test
    void 실제_예약_신청이_커밋되면_이벤트가_나온다_FR_CRS_02_1() throws Exception {
        long region = region();
        long base = bookableStore(region, "기준 카페", "cafe");
        bookableStore(region, "구름 디저트", "dessert");

        String body = """
                {"storeId":"%d","startAt":"2026-12-02T09:00:00Z","partySize":2,"shareConsent":true,
                 "guest":{"name":"손님","phone":"0109%07d"}}
                """.formatted(base, (int) (System.nanoTime() % 10_000_000));
        mvc.perform(post("/api/v1/reservations").with(TestCsrf.csrf(mvc))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        long reservation = jdbc.queryForObject("SELECT id FROM reservation WHERE store_id = ?", Long.class, base);
        assertThat(events.stream(NextStoreRecommendedEvent.class))
                .extracting(NextStoreRecommendedEvent::reservationId, NextStoreRecommendedEvent::storeId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(reservation, base));
    }

    // ---- 준비 ----

    private long region() {
        long id = insert("INSERT INTO region (name) VALUES ('추천 알림 권역')");
        regions.add(id);
        return id;
    }

    private long store(long region, String name, String category) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status) VALUES ("
                + region + ", '" + name + "', '" + category + "', 'center', 'approved')");
    }

    private long bookableStore(long region, String name, String category) {
        return insert("""
                INSERT INTO store (region_id, name, category, alley, join_status, open_time, close_time,
                                   capacity_per_slot, booking_window_days)
                VALUES (%d, '%s', '%s', 'center', 'approved', '10:00', '22:00', 5, 7)
                """.formatted(region, name, category));
    }

    /** 2026-12-02 18:00 KST 신청 예약. */
    private long reservation(long store, long customer) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, '2026-12-02 09:00:00', 2, 'requested', '2026-12-01 01:00:00')
                """, "N" + UUID.randomUUID().toString().substring(0, 11), store, customer);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
