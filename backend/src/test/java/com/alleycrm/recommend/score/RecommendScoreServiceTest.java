package com.alleycrm.recommend.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.RecommendScoreService.RecommendScore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 추천 최종 점수 서비스(E6): DB의 방문 기록 · 공공데이터 · 설정값으로 계산한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RecommendScoreServiceTest {

    /** 2026-12-04(금) 19:00 Asia/Seoul → 시간대 항은 금요일 · 다음 시간대(21 ~ 24시). */
    private static final Instant FRIDAY_7PM = Instant.parse("2026-12-04T10:00:00Z");
    /** 2026-12-07(월) 09:00 Asia/Seoul → 월요일 · 다음 시간대(11 ~ 14시). */
    private static final Instant MONDAY_9AM = Instant.parse("2026-12-07T00:00:00Z");

    @Autowired
    RecommendScoreService service;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long base;
    long dessert;
    long pub;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM public_stat");
        region = insert("INSERT INTO region (name) VALUES ('최종 점수 권역')");
        base = store("cafe", 0);
        dessert = store("dessert", 10);
        pub = store("pub", 20);
        stat("day_share", "dessert", "fri", "0.1");
        stat("day_share", "pub", "fri", "0.4");
        stat("day_share", "dessert", "mon", "0.2");
        stat("day_share", "pub", "mon", "0.05");
        stat("time_band_share", "dessert", "late", "0.1");
        stat("time_band_share", "pub", "late", "0.5");
        stat("time_band_share", "dessert", "lunch", "0.4");
        stat("time_band_share", "pub", "lunch", "0.05");
    }

    @Test
    void 기준_시각이_금요일_19시면_시간대_항을_그_시각으로_계산한다_FR_CRS_03_4() {
        List<RecommendScore> friday = service.score(base, List.of(dessert, pub), FRIDAY_7PM, Profile.UNKNOWN);
        List<RecommendScore> monday = service.score(base, List.of(dessert, pub), MONDAY_9AM, Profile.UNKNOWN);

        assertThat(friday.get(1).defaultTerms().get(DefaultScoreTerm.TIME_BAND)).isEqualTo(1.0); // 주점
        assertThat(monday.get(0).defaultTerms().get(DefaultScoreTerm.TIME_BAND)).isEqualTo(1.0); // 디저트
    }

    @Test
    void 권역의_방문_처리된_예약으로_실제_기록을_섞는다() {
        for (int i = 0; i < 12; i++) {
            long customer = customer();
            visit(customer, base, LocalDateTime.of(2026, 10, 2, 3, 0), "visited");
            visit(customer, dessert, LocalDateTime.of(2026, 10, 2, 5, 0), "visited");
        }
        long canceledOnly = customer();
        visit(canceledOnly, base, LocalDateTime.of(2026, 10, 3, 3, 0), "visited");
        visit(canceledOnly, pub, LocalDateTime.of(2026, 10, 3, 5, 0), "canceled");

        List<RecommendScore> scores = service.score(base, List.of(dessert, pub), FRIDAY_7PM, Profile.UNKNOWN);

        RecommendScore toDessert = scores.get(0);
        assertThat(toDessert.nA()).isEqualTo(13);
        assertThat(toDessert.nAB()).isEqualTo(12);
        assertThat(toDessert.lift1()).isNotNull();
        assertThat(toDessert.visitDataBased()).isTrue();
        assertThat(toDessert.finalScore()).isNotEqualTo(toDessert.lift0());
        RecommendScore toPub = scores.get(1);
        assertThat(toPub.nAB()).isZero(); // 취소된 예약은 세지 않는다
        assertThat(toPub.finalScore()).isEqualTo(toPub.lift0());
    }

    private long store(String category, int x) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, map_x, map_y) VALUES ("
                + region + ", '" + category + "', '" + category + "', 'center', 'approved', " + x + ", 0)");
    }

    private long customer() {
        return insert("INSERT INTO customer (type) VALUES ('member')");
    }

    private void visit(long customer, long store, LocalDateTime startUtc, String status) {
        jdbc.update("""
                INSERT INTO reservation (reservation_no, store_id, customer_id, start_at, party_size, status, requested_at)
                VALUES (?, ?, ?, ?, 2, ?, ?)
                """, "R" + UUID.randomUUID().toString().substring(0, 12), store, customer, startUtc, status,
                startUtc.minusDays(1));
    }

    private void stat(String kind, String to, String slot, String value) {
        jdbc.update("""
                INSERT INTO public_stat (kind, category_to, slot, value, source, period)
                VALUES (?, ?, ?, ?, '테스트 출처', '테스트 기간')
                """, kind, to, slot, new BigDecimal(value));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
