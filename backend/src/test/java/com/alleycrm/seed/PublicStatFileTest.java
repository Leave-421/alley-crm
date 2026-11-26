package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.recommend.domain.PublicStatKind;
import com.alleycrm.recommend.domain.TimeBand;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 공공데이터 기본값 파일(E2, 06-data PUBLIC_STAT, NFR-10, 결정 11-26). DB 없이 확인한다. */
class PublicStatFileTest {

    private static final List<PublicStatFile.Row> ROWS = PublicStatFile.load();

    @Test
    void 매력도_CLQ_요일_시간대_성별_연령대_비중이_모두_있다() {
        Map<PublicStatKind, Long> byKind = ROWS.stream()
                .collect(Collectors.groupingBy(PublicStatFile.Row::kind, Collectors.counting()));

        assertThat(byKind.keySet()).containsExactlyInAnyOrder(PublicStatKind.values());
        assertThat(byKind.get(PublicStatKind.CLQ)).isEqualTo(64L); // 8 × 8 업종
        assertThat(byKind.get(PublicStatKind.TIME_BAND_SHARE)).isEqualTo(6L * byKind.get(PublicStatKind.ATTRACTIVENESS));
        assertThat(byKind.get(PublicStatKind.DAY_SHARE)).isEqualTo(7L * byKind.get(PublicStatKind.ATTRACTIVENESS));
    }

    @Test
    void 공방_소품샵은_매출_행이_없고_CLQ만_있다_P_4() {
        Set<String> salesCategories = ROWS.stream().filter(r -> r.kind() != PublicStatKind.CLQ)
                .map(PublicStatFile.Row::categoryTo).collect(Collectors.toSet());

        assertThat(salesCategories).containsExactlyInAnyOrder("restaurant", "cafe", "dessert", "pub", "bookstore", "stationery");
        assertThat(ROWS).filteredOn(r -> r.kind() == PublicStatKind.CLQ)
                .extracting(PublicStatFile.Row::categoryFrom).contains("workshop", "gift_shop");
    }

    @Test
    void 모든_행에_출처와_기준_기간이_있다_NFR_10() {
        assertThat(ROWS).allSatisfy(r -> {
            assertThat(r.source()).isNotBlank();
            assertThat(r.period()).isNotBlank();
        });
        assertThat(ROWS).filteredOn(r -> r.kind() == PublicStatKind.CLQ)
                .extracting(PublicStatFile.Row::source).containsOnly("소상공인시장진흥공단 상가(상권)정보 (15083033)");
        assertThat(ROWS).filteredOn(r -> r.kind() != PublicStatKind.CLQ)
                .extracting(PublicStatFile.Row::period).containsOnly("2025년 4분기");
    }

    @Test
    void 비중_합이_1이_아니면_넣지_않는다() {
        String bad = PublicStatFile.HEADER + "\n"
                + "attractiveness,,cafe,,,,0.4,src,2025년 4분기\n"
                + "attractiveness,,pub,,,,0.4,src,2025년 4분기\n";

        assertThatThrownBy(() -> PublicStatFile.parse(bad)).hasMessageContaining("비중 합");
    }

    @Test
    void 모르는_값이나_빈_출처나_다른_머리글이면_넣지_않는다() {
        assertThatThrownBy(() -> PublicStatFile.parse(PublicStatFile.HEADER + "\nfoo,,cafe,,,,1,src,p\n"))
                .hasMessageContaining("모르는 kind");
        assertThatThrownBy(() -> PublicStatFile.parse(PublicStatFile.HEADER + "\nclq,cafe,karaoke,,,,1,src,p\n"))
                .hasMessageContaining("category_to");
        assertThatThrownBy(() -> PublicStatFile.parse(PublicStatFile.HEADER + "\ntime_band_share,,cafe,noon,,,1,src,p\n"))
                .hasMessageContaining("칸 조합");
        assertThatThrownBy(() -> PublicStatFile.parse(PublicStatFile.HEADER + "\nclq,cafe,pub,,,,1, ,p\n"))
                .hasMessageContaining("출처");
        assertThatThrownBy(() -> PublicStatFile.parse("kind,value\n")).hasMessageContaining("머리글");
    }

    @Test
    void 따옴표_안_쉼표를_한_칸으로_읽는다() {
        assertThat(PublicStatFile.splitCsv("a,\"b, c\",\"d\"\"e\",")).containsExactly("a", "b, c", "d\"e", "");
    }

    @Test
    void 시간대는_추정매출_6구간이다() {
        assertThat(TimeBand.of(LocalTime.of(5, 59))).isEqualTo(TimeBand.NIGHT);
        assertThat(TimeBand.of(LocalTime.of(6, 0))).isEqualTo(TimeBand.MORNING);
        assertThat(TimeBand.of(LocalTime.of(13, 30))).isEqualTo(TimeBand.LUNCH);
        assertThat(TimeBand.of(LocalTime.of(16, 59))).isEqualTo(TimeBand.AFTERNOON);
        assertThat(TimeBand.of(LocalTime.of(19, 0))).isEqualTo(TimeBand.EVENING);
        assertThat(TimeBand.of(LocalTime.of(23, 59))).isEqualTo(TimeBand.LATE);
        assertThat(TimeBand.fromCode("evening")).contains(TimeBand.EVENING);
    }
}
