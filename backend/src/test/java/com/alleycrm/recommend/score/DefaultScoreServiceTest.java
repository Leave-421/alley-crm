package com.alleycrm.recommend.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.alleycrm.TestcontainersConfiguration;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Score;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 기본값 점수 서비스(E5): 가게 좌표 · 업종, public_stat, 권역 설정값을 읽어 계산한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DefaultScoreServiceTest {

    /** 2026-12-04(금) 16:00 Asia/Seoul → 다음 시간대는 저녁(17 ~ 21시). */
    private static final Instant FRIDAY_4PM = Instant.parse("2026-12-04T07:00:00Z");

    @Autowired
    DefaultScoreService service;

    @Autowired
    SettingService settings;

    @Autowired
    JdbcTemplate jdbc;

    long region;
    long base;
    long dessert;
    long pub;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM public_stat");
        region = insert("INSERT INTO region (name) VALUES ('기본값 권역')");
        base = store("cafe", 0, 0);
        dessert = store("dessert", 10, 0);
        pub = store("pub", 200, 0);
        stat("attractiveness", null, "dessert", null, "0.2");
        stat("attractiveness", null, "pub", null, "0.8");
        stat("clq", "cafe", "dessert", null, "1.5");
        stat("clq", "cafe", "pub", null, "0.5");
        stat("day_share", null, "dessert", "fri", "0.1");
        stat("day_share", null, "pub", "fri", "0.4");
        stat("time_band_share", null, "dessert", "evening", "0.2");
        stat("time_band_share", null, "pub", "evening", "0.5");
    }

    @Test
    void 후보마다_항별_점수와_lift0을_준다_손님_정보가_없으면_손님층_항_없이() {
        List<Score> scores = service.score(base, List.of(dessert, pub), FRIDAY_4PM, Profile.UNKNOWN);

        assertThat(scores).extracting(Score::storeId).containsExactly(dessert, pub);
        assertThat(scores).allSatisfy(s -> assertThat(s.terms()).doesNotContainKey(DefaultScoreTerm.DEMOGRAPHIC));
        assertThat(scores.stream().mapToDouble(Score::lift0).average().orElseThrow()).isCloseTo(1.0, within(1e-9));
        // 금요일 16시 → 다음 시간대 저녁: 주점 0.4 × 0.5 > 디저트 0.1 × 0.2
        assertThat(scores.get(1).terms().get(DefaultScoreTerm.TIME_BAND)).isEqualTo(1.0);
    }

    @Test
    void 가중치는_기준_가게_권역의_설정값을_쓴다_NFR_04() {
        for (DefaultScoreTerm term : DefaultScoreTerm.values()) {
            settings.set(region, term.weightKey(), term == DefaultScoreTerm.DISTANCE ? "1" : "0");
        }
        settings.set(region, SettingKey.RECOMMEND_DISTANCE_EXPONENT, "2");

        List<Score> scores = service.score(base, List.of(dessert, pub), FRIDAY_4PM, Profile.UNKNOWN);

        assertThat(scores).allSatisfy(s -> assertThat(s.terms()).containsOnlyKeys(DefaultScoreTerm.DISTANCE));
        assertThat(scores.get(0).s0()).isGreaterThan(scores.get(1).s0()); // 가까운 디저트
    }

    @Test
    void 기준_가게가_없거나_후보가_없으면_빈_목록() {
        assertThat(service.score(999_999_999L, List.of(dessert), FRIDAY_4PM, Profile.UNKNOWN)).isEmpty();
        assertThat(service.score(base, List.of(), FRIDAY_4PM, Profile.UNKNOWN)).isEmpty();
    }

    private long store(String category, int x, int y) {
        return insert("INSERT INTO store (region_id, name, category, alley, join_status, map_x, map_y) VALUES ("
                + region + ", '" + category + "', '" + category + "', 'center', 'approved', " + x + ", " + y + ")");
    }

    private void stat(String kind, String from, String to, String slot, String value) {
        jdbc.update("""
                INSERT INTO public_stat (kind, category_from, category_to, slot, value, source, period)
                VALUES (?, ?, ?, ?, ?, '테스트 출처', '테스트 기간')
                """, kind, from, to, slot, new java.math.BigDecimal(value));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
