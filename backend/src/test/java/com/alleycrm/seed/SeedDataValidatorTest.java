package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

/** 잘못된 시드 데이터를 넣기 전에 막는지. DB 없이 돈다. */
class SeedDataValidatorTest {

    private final SeedRegionData valid = SeedDataLoader.loadRegionStores();

    @Test
    void 가게가_15곳이_아니면_거부() {
        List<SeedRegionData.Store> fourteen = new ArrayList<>(valid.stores());
        fourteen.removeLast();

        assertThat(SeedDataValidator.violations(withStores(fourteen)))
                .anyMatch(m -> m.contains("참여 가게 수 14곳"));
    }

    @Test
    void 한_골목에_5곳이_아니면_거부() {
        SeedRegionData data = withFirstStore(s -> edit(s).alley(SeedAlley.CENTER).build());

        assertThat(SeedDataValidator.violations(data))
                .anyMatch(m -> m.contains("정문 앞") && m.contains("4곳"))
                .anyMatch(m -> m.contains("중앙") && m.contains("6곳"));
    }

    @Test
    void 가게_이름에_실제_지명이_있으면_거부_NFR_07_3() {
        SeedRegionData data = withFirstStore(s -> edit(s).name("신촌 커피").build());

        assertThat(SeedDataValidator.violations(data)).anyMatch(m -> m.contains("실제 지명 '신촌'"));
    }

    @Test
    void 주소에_실제_지명이_있거나_비어_있으면_거부() {
        SeedRegionData placeName = withFirstStore(s -> edit(s).address("홍대 앞길 3").build());
        SeedRegionData blank = withFirstStore(s -> edit(s).address(" ").build());

        assertThat(SeedDataValidator.violations(placeName)).anyMatch(m -> m.contains("주소") && m.contains("'홍대'"));
        assertThat(SeedDataValidator.violations(blank)).anyMatch(m -> m.contains("주소가 비어 있음"));
    }

    @Test
    void 가게_이름이_겹치면_거부() {
        String other = valid.stores().get(1).name();
        SeedRegionData data = withFirstStore(s -> edit(s).name(other).build());

        assertThat(SeedDataValidator.violations(data)).anyMatch(m -> m.contains("가게 이름 중복"));
    }

    @Test
    void 업종이_빠지면_거부() {
        // 유일한 디저트 가게(S08)를 카페로 바꾼다.
        List<SeedRegionData.Store> stores = valid.stores().stream()
                .map(s -> s.category() == SeedCategory.DESSERT ? edit(s).category(SeedCategory.CAFE).build() : s)
                .toList();

        assertThat(SeedDataValidator.violations(withStores(stores))).anyMatch(m -> m.contains("빠진 업종") && m.contains("디저트"));
    }

    @Test
    void 좌표가_평면_밖이거나_겹치면_거부() {
        SeedRegionData.Store second = valid.stores().get(1);
        SeedRegionData outside = withFirstStore(s -> edit(s).mapX(-1).build());
        SeedRegionData overlap = withFirstStore(s -> edit(s).mapX(second.mapX()).mapY(second.mapY()).build());

        assertThat(SeedDataValidator.violations(outside)).anyMatch(m -> m.contains("평면 밖"));
        assertThat(SeedDataValidator.violations(overlap)).anyMatch(m -> m.contains("좌표가 같음"));
    }

    @Test
    void 영업시간이_없거나_시작이_종료보다_늦으면_거부() {
        SeedRegionData missing = withFirstStore(s -> edit(s).openTime(null).build());
        SeedRegionData reversed = withFirstStore(s -> edit(s).openTime(LocalTime.of(22, 0)).closeTime(LocalTime.of(2, 0)).build());

        assertThat(SeedDataValidator.violations(missing)).anyMatch(m -> m.contains("영업 시작 · 종료 시각이 비어 있음"));
        assertThat(SeedDataValidator.violations(reversed)).anyMatch(m -> m.contains("영업 시작이 종료보다 늦거나 같음"));
    }

    @Test
    void 휴무일은_계약_요일_값만_중복_없이() {
        SeedRegionData unknown = withFirstStore(s -> edit(s).closedDays(List.of("monday")).build());
        SeedRegionData duplicate = withFirstStore(s -> edit(s).closedDays(List.of("sun", "sun")).build());
        SeedRegionData everyDay = withFirstStore(s -> edit(s).closedDays(SeedDataValidator.DAYS).build());

        assertThat(SeedDataValidator.violations(unknown)).anyMatch(m -> m.contains("모르는 휴무 요일 'monday'"));
        assertThat(SeedDataValidator.violations(duplicate)).anyMatch(m -> m.contains("휴무 요일 중복"));
        assertThat(SeedDataValidator.violations(everyDay)).anyMatch(m -> m.contains("모든 요일이 휴무"));
    }

    @Test
    void 취소_마감이_24시간보다_이르거나_최대_인원이_0이면_거부() {
        SeedRegionData deadline = withFirstStore(s -> edit(s).cancelDeadlineHours(25).build());
        SeedRegionData party = withFirstStore(s -> edit(s).maxPartySize(0).build());

        assertThat(SeedDataValidator.violations(deadline)).anyMatch(m -> m.contains("취소 마감"));
        assertThat(SeedDataValidator.violations(party)).anyMatch(m -> m.contains("한 팀 최대 인원"));
    }

    @Test
    void validate는_어긋난_점을_모아_예외로_알린다() {
        List<SeedRegionData.Store> fourteen = new ArrayList<>(valid.stores());
        fourteen.removeLast();

        assertThatThrownBy(() -> SeedDataValidator.validate(withStores(fourteen)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참여 가게 수 14곳");
    }

    @Test
    void 모르는_업종_값이나_모르는_칸이나_빠진_칸은_읽을_때_실패() {
        String unknownCategory = """
                {"regionName":"가상 권역","plane":{"width":10,"height":10},"stores":[
                 {"code":"S01","name":"가","category":"karaoke","alley":"center","mapX":1,"mapY":1,"address":"가길 1",
                  "openTime":"10:00","closeTime":"20:00","closedDays":[],"slotMinutes":60,"capacityPerSlot":1,
                  "bookingWindowDays":1,"cancelDeadlineHours":1,"depositPerPerson":0,"maxPartySize":null}]}
                """;
        String unknownField = """
                {"regionName":"가상 권역","plane":{"width":10,"height":10},"stores":[],"extra":1}
                """;
        String missingField = """
                {"regionName":"가상 권역","plane":{"width":10,"height":10},"stores":[
                 {"code":"S01","name":"가","category":"cafe","alley":"center","mapX":1,"mapY":1,"address":"가길 1",
                  "openTime":"10:00","closeTime":"20:00","closedDays":[],"slotMinutes":60,"capacityPerSlot":1,
                  "bookingWindowDays":1,"cancelDeadlineHours":1,"depositPerPerson":0}]}
                """;

        assertThatThrownBy(() -> SeedDataLoader.parse(unknownCategory)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> SeedDataLoader.parse(unknownField)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> SeedDataLoader.parse(missingField)).isInstanceOf(JacksonException.class);
    }

    private SeedRegionData withStores(List<SeedRegionData.Store> stores) {
        return new SeedRegionData(valid.regionName(), valid.plane(), stores);
    }

    private SeedRegionData withFirstStore(UnaryOperator<SeedRegionData.Store> change) {
        List<SeedRegionData.Store> stores = new ArrayList<>(valid.stores());
        stores.set(0, change.apply(stores.getFirst()));
        return withStores(stores);
    }

    private static StoreEdit edit(SeedRegionData.Store s) {
        return new StoreEdit(s);
    }

    /** 테스트에서 가게 한 곳의 칸 몇 개만 바꾸기 위한 도우미. */
    private static final class StoreEdit {
        private String name;
        private SeedCategory category;
        private SeedAlley alley;
        private int mapX;
        private int mapY;
        private String address;
        private LocalTime openTime;
        private LocalTime closeTime;
        private List<String> closedDays;
        private int cancelDeadlineHours;
        private Integer maxPartySize;
        private final SeedRegionData.Store base;

        StoreEdit(SeedRegionData.Store s) {
            base = s;
            name = s.name();
            category = s.category();
            alley = s.alley();
            mapX = s.mapX();
            mapY = s.mapY();
            address = s.address();
            openTime = s.openTime();
            closeTime = s.closeTime();
            closedDays = s.closedDays();
            cancelDeadlineHours = s.cancelDeadlineHours();
            maxPartySize = s.maxPartySize();
        }

        StoreEdit name(String v) { name = v; return this; }
        StoreEdit category(SeedCategory v) { category = v; return this; }
        StoreEdit alley(SeedAlley v) { alley = v; return this; }
        StoreEdit mapX(int v) { mapX = v; return this; }
        StoreEdit mapY(int v) { mapY = v; return this; }
        StoreEdit address(String v) { address = v; return this; }
        StoreEdit openTime(LocalTime v) { openTime = v; return this; }
        StoreEdit closeTime(LocalTime v) { closeTime = v; return this; }
        StoreEdit closedDays(List<String> v) { closedDays = v; return this; }
        StoreEdit cancelDeadlineHours(int v) { cancelDeadlineHours = v; return this; }
        StoreEdit maxPartySize(Integer v) { maxPartySize = v; return this; }

        SeedRegionData.Store build() {
            return new SeedRegionData.Store(base.code(), name, category, alley, mapX, mapY, address, openTime, closeTime,
                    closedDays, base.slotMinutes(), base.capacityPerSlot(), base.bookingWindowDays(), cancelDeadlineHours,
                    base.depositPerPerson(), maxPartySize);
        }
    }
}
