package com.alleycrm.seed;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시드 데이터를 DB에 넣기 전에 검사한다. 하나라도 어긋나면 아무것도 넣지 않는다.
 * 기준: NFR-07(참여 가게 15곳 · 업종 혼합 · 가상 이름 · 실제 지명 없음), 결정 11-24 (4)(골목 3개 × 5곳),
 * 11-24 (5)(영업시간 · 휴무일 필수, 주소), 05-policy 「취소와 환불」(취소 마감은 예약 24시간 전보다 이르게 못 잡음).
 */
public final class SeedDataValidator {

    static final int STORE_COUNT = 15;
    static final int STORES_PER_ALLEY = 5;
    /** 05-policy 「설정값」 전액 환불 기한(정해진 값). 가게 취소 마감은 이보다 이르게 잡을 수 없다. */
    static final int MAX_CANCEL_DEADLINE_HOURS = 24;
    /** 휴무 요일 값(CONTRACTS.md 「3」 `store.closedDays`). */
    static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    /**
     * 가게 · 권역 이름에 쓰면 안 되는 실제 지명(NFR-07 #3). 대학가 상권과 서울 자치구 이름 위주다.
     * 완전한 목록은 아니며, 사람이 검토하는 것을 대신하지 않는다.
     */
    static final List<String> REAL_PLACE_NAMES = List.of(
            "서울", "신촌", "홍대", "홍익", "연남", "연희", "합정", "상수", "망원", "이대", "이화", "아현",
            "건대", "건국", "성수", "안암", "고려대", "회기", "경희", "혜화", "대학로", "성균관", "숙대", "숙명",
            "서울대", "관악", "신림", "낙성대", "왕십리", "한양대", "노원", "공릉", "외대", "이문", "휘경",
            "중앙대", "흑석", "마포", "서대문", "성북", "광진", "동대문", "성동", "종로", "강남");

    private SeedDataValidator() {
    }

    /** 어긋난 점을 모두 모아 한 번에 알린다. */
    public static void validate(SeedRegionData data) {
        List<String> violations = violations(data);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("시드 데이터가 맞지 않습니다:\n- " + String.join("\n- ", violations));
        }
    }

    static List<String> violations(SeedRegionData data) {
        List<String> v = new ArrayList<>();
        if (isBlank(data.regionName())) {
            v.add("권역 이름이 비어 있음");
        } else {
            checkVirtualName("권역", data.regionName(), v);
        }
        if (data.plane() == null || data.plane().width() <= 0 || data.plane().height() <= 0) {
            v.add("좌표 평면 크기가 0 이하");
        }
        List<SeedRegionData.Store> stores = data.stores() == null ? List.of() : data.stores();
        if (stores.size() != STORE_COUNT) {
            v.add("참여 가게 수 " + stores.size() + "곳 (" + STORE_COUNT + "곳이어야 함)");
        }

        Set<String> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> points = new HashSet<>();
        Map<SeedAlley, Integer> perAlley = new EnumMap<>(SeedAlley.class);
        EnumSet<SeedCategory> categories = EnumSet.noneOf(SeedCategory.class);

        for (SeedRegionData.Store s : stores) {
            String label = isBlank(s.code()) ? "(코드 없음)" : s.code();
            if (isBlank(s.code())) {
                v.add("가게 코드가 비어 있음");
            } else if (!codes.add(s.code())) {
                v.add(label + ": 가게 코드 중복");
            }
            if (isBlank(s.name())) {
                v.add(label + ": 가게 이름이 비어 있음");
            } else {
                if (!names.add(s.name())) {
                    v.add(label + ": 가게 이름 중복 '" + s.name() + "'");
                }
                checkVirtualName(label, s.name(), v);
            }
            if (s.category() == null) {
                v.add(label + ": 업종이 비어 있음");
            } else {
                categories.add(s.category());
            }
            if (s.alley() == null) {
                v.add(label + ": 골목이 비어 있음");
            } else {
                perAlley.merge(s.alley(), 1, Integer::sum);
            }
            if (isBlank(s.address())) {
                v.add(label + ": 주소가 비어 있음");
            } else {
                checkVirtualName(label + " 주소", s.address(), v);
            }
            checkPoint(label, s, data.plane(), points, v);
            checkHours(label, s, v);
            checkReservationSettings(label, s, v);
        }

        for (SeedAlley alley : SeedAlley.values()) {
            int count = perAlley.getOrDefault(alley, 0);
            if (count != STORES_PER_ALLEY) {
                v.add("골목 '" + alley.label() + "' 가게 " + count + "곳 (" + STORES_PER_ALLEY + "곳이어야 함)");
            }
        }
        EnumSet<SeedCategory> missing = EnumSet.allOf(SeedCategory.class);
        missing.removeAll(categories);
        if (!missing.isEmpty()) {
            v.add("빠진 업종: " + missing.stream().map(SeedCategory::label).toList());
        }
        return v;
    }

    private static void checkVirtualName(String label, String name, List<String> v) {
        for (String place : REAL_PLACE_NAMES) {
            if (name.contains(place)) {
                v.add(label + ": 이름에 실제 지명 '" + place + "'이 들어 있음");
            }
        }
    }

    private static void checkPoint(String label, SeedRegionData.Store s, SeedRegionData.Plane plane,
            Set<String> points, List<String> v) {
        if (plane != null && (s.mapX() < 0 || s.mapX() > plane.width() || s.mapY() < 0 || s.mapY() > plane.height())) {
            v.add(label + ": 좌표 (" + s.mapX() + ", " + s.mapY() + ")가 평면 밖");
        }
        if (!points.add(s.mapX() + "," + s.mapY())) {
            v.add(label + ": 다른 가게와 좌표가 같음");
        }
    }

    /**
     * 영업시간 · 휴무일은 필수다(11-24 (5), 예약 가능 시간 계산 기준). 자정을 넘기는 영업시간은 정책에 정해진 것이 없어
     * 시드에서는 쓰지 않는다(시작 &lt; 종료).
     */
    private static void checkHours(String label, SeedRegionData.Store s, List<String> v) {
        if (s.openTime() == null || s.closeTime() == null) {
            v.add(label + ": 영업 시작 · 종료 시각이 비어 있음");
        } else if (!s.openTime().isBefore(s.closeTime())) {
            v.add(label + ": 영업 시작이 종료보다 늦거나 같음");
        }
        if (s.closedDays() == null) {
            v.add(label + ": 휴무일 목록이 없음(연중무휴는 빈 목록)");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String day : s.closedDays()) {
            if (!DAYS.contains(day)) {
                v.add(label + ": 모르는 휴무 요일 '" + day + "'");
            } else if (!seen.add(day)) {
                v.add(label + ": 휴무 요일 중복 '" + day + "'");
            }
        }
        if (seen.size() == DAYS.size()) {
            v.add(label + ": 모든 요일이 휴무");
        }
    }

    private static void checkReservationSettings(String label, SeedRegionData.Store s, List<String> v) {
        if (s.maxPartySize() != null && s.maxPartySize() <= 0) {
            v.add(label + ": 한 팀 최대 인원은 1 이상");
        }
        if (s.slotMinutes() <= 0) {
            v.add(label + ": 슬롯 단위는 1분 이상");
        }
        if (s.capacityPerSlot() <= 0) {
            v.add(label + ": 동시에 받는 예약 수는 1 이상");
        }
        if (s.bookingWindowDays() <= 0) {
            v.add(label + ": 예약 가능 기간은 1일 이상");
        }
        if (s.cancelDeadlineHours() < 0 || s.cancelDeadlineHours() > MAX_CANCEL_DEADLINE_HOURS) {
            v.add(label + ": 취소 마감은 0 ~ " + MAX_CANCEL_DEADLINE_HOURS + "시간");
        }
        if (s.depositPerPerson() < 0) {
            v.add(label + ": 인원당 예약금은 0 이상");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
