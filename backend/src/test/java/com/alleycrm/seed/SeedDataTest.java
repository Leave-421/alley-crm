package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 실제 시드 파일 {@code seed/region-stores.json} 검사. DB 없이 돈다. */
class SeedDataTest {

    private final SeedRegionData data = SeedDataLoader.loadRegionStores();

    @Test
    void 시드_파일은_검사를_통과한다() {
        assertThat(SeedDataValidator.violations(data)).isEmpty();
    }

    @Test
    void NFR_07_1_참여_가게_15곳_업종_혼합_가게_이름은_가상() {
        assertThat(data.stores()).hasSize(15);
        assertThat(data.stores().stream().map(SeedRegionData.Store::category).distinct().count())
                .as("업종 혼합").isGreaterThan(1);
        assertThat(data.stores()).allSatisfy(s ->
                assertThat(SeedDataValidator.REAL_PLACE_NAMES).noneMatch(place -> s.name().contains(place)));
    }

    @Test
    void NFR_07_3_권역_이름에_실제_지명이_없다() {
        assertThat(SeedDataValidator.REAL_PLACE_NAMES).noneMatch(place -> data.regionName().contains(place));
    }

    @Test
    void 골목_3개에_5곳씩_결정_11_24_4() {
        Map<SeedAlley, Long> perAlley = data.stores().stream()
                .collect(Collectors.groupingBy(SeedRegionData.Store::alley, Collectors.counting()));
        assertThat(perAlley).containsOnlyKeys(SeedAlley.values());
        assertThat(perAlley.values()).containsOnly(5L);
    }

    @Test
    void 업종_구성은_잠정값_Q_25와_같다() {
        // 03-requirements Q-25: 음식점 4 · 카페 3 · 디저트 1 · 주점 2 · 공방 2 · 서점 1 · 소품샵 1 · 문구점 1 (잠정).
        // 구성이 확정되면 이 기대값과 시드 파일을 함께 바꾼다.
        Map<SeedCategory, Long> perCategory = data.stores().stream()
                .collect(Collectors.groupingBy(SeedRegionData.Store::category, Collectors.counting()));
        assertThat(perCategory).isEqualTo(Map.of(
                SeedCategory.RESTAURANT, 4L,
                SeedCategory.CAFE, 3L,
                SeedCategory.DESSERT, 1L,
                SeedCategory.PUB, 2L,
                SeedCategory.WORKSHOP, 2L,
                SeedCategory.BOOKSTORE, 1L,
                SeedCategory.GIFT_SHOP, 1L,
                SeedCategory.STATIONERY, 1L));
    }

    @Test
    void 가게마다_영업시간_휴무일_가상_주소가_있다_11_24_5() {
        assertThat(data.stores()).allSatisfy(s -> {
            assertThat(s.openTime()).isBefore(s.closeTime());
            assertThat(s.closedDays()).isNotNull();
            assertThat(s.address()).isNotBlank();
            assertThat(SeedDataValidator.REAL_PLACE_NAMES).noneMatch(place -> s.address().contains(place));
        });
    }

    @Test
    void 가게_코드는_가상_데이터가_가리킬_수_있게_고정_형식이다() {
        Map<String, SeedRegionData.Store> byCode = data.stores().stream()
                .collect(Collectors.toMap(SeedRegionData.Store::code, Function.identity()));
        assertThat(byCode.keySet()).allMatch(code -> code.matches("S\\d{2}"));
    }
}
