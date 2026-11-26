package com.alleycrm.seed;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 가상 데이터 1(E16 #107, 06-data 「가상 데이터 생성」 2 ~ 5단계): 가상 손님 → 예약 → 일부 취소 → 방문 처리.
 * DB 없이 계획만 만든다. 같은 seed면 같은 결과다(재현 가능).
 *
 * <ul>
 *   <li>손님: 회원 · 비회원, 성별 · 연령대(대학가라 20대 이하가 많음). 손님 수는 미결이라 설정값(NFR-07)</li>
 *   <li>예약: 학기 중 9 ~ 11월(운영 시간대), 업종별 시간 분포, 가게 영업시간 · 휴무일 · 슬롯 · 슬롯당 팀 수 · 최대 인원을 지킨다</li>
 *   <li>금요일 저녁 주점 예약 집중(NFR-08 패턴 2)</li>
 *   <li>취소 10% 안팎: 하루 전 · 당일 손님 취소, 가게 거절(NFR-08)</li>
 *   <li>나머지는 방문 처리, 방문 금액(쿠폰 할인 전)을 업종 · 인원으로 만든다</li>
 * </ul>
 * 같은 날 이어 방문(20대 카페 → 디저트) · 노출 · 코스 제안 · 소개는 가상 데이터 2(E17)가 이 결과 위에 더한다.
 */
public final class DemoReservationPlanner {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    /** 학기 중 9 ~ 11월(NFR-07). */
    public static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 1);
    public static final LocalDate PERIOD_END = LocalDate.of(2026, 11, 30);

    /** 예약번호 글자(CONTRACTS_API 「예약 응답」: 헷갈리는 0 O 1 I 제외). */
    private static final String RESERVATION_NO_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_ATTEMPTS = 20;

    static final double CANCEL_DAY_BEFORE = 0.06;
    static final double CANCEL_SAME_DAY = 0.03;
    static final double STORE_REJECTED = 0.01;

    public record Customer(int index, String type, String name, String phone, String gender, String ageGroup) {
    }

    public record Reservation(
            String reservationNo,
            int customerIndex,
            String storeCode,
            Instant startAt,
            int partySize,
            Instant requestedAt,
            String status,
            String cancelReason,
            Instant confirmedAt,
            Instant visitedAt,
            Instant canceledAt,
            Integer visitAmount,
            int depositAmount) {
    }

    public record Plan(List<Customer> customers, List<Reservation> reservations) {
    }

    private final List<SeedRegionData.Store> stores;
    private final Random random;

    private DemoReservationPlanner(List<SeedRegionData.Store> stores, long seed) {
        this.stores = stores;
        this.random = new Random(seed);
    }

    public static Plan plan(List<SeedRegionData.Store> stores, int customerCount, long seed) {
        if (customerCount < 1) {
            throw new IllegalArgumentException("가상 손님 수는 1 이상이어야 합니다: " + customerCount);
        }
        return new DemoReservationPlanner(stores, seed).build(customerCount);
    }

    private Plan build(int customerCount) {
        List<Customer> customers = new ArrayList<>();
        for (int i = 0; i < customerCount; i++) {
            customers.add(customer(i));
        }
        List<Reservation> reservations = new ArrayList<>();
        Map<String, Integer> teamsPerSlot = new HashMap<>();
        Set<String> reservationNos = new HashSet<>();
        for (Customer customer : customers) {
            int count = reservationsPerCustomer();
            for (int k = 0; k < count; k++) {
                Reservation r = reservation(customer, teamsPerSlot, reservationNos);
                if (r != null) {
                    reservations.add(r);
                }
            }
        }
        return new Plan(customers, reservations);
    }

    // ---- 손님 ----

    private Customer customer(int index) {
        boolean member = random.nextDouble() < 0.6;
        String gender = pick(new String[] {"female", "male", null}, new double[] {0.48, 0.44, 0.08});
        String ageGroup = pick(new String[] {"age_20_or_under", "age_30s", "age_40s", "age_50_or_over", null},
                new double[] {0.55, 0.22, 0.11, 0.07, 0.05});
        if (member) {
            return new Customer(index, "member", "가상회원" + (index + 1), null, gender, ageGroup);
        }
        // 비회원은 이름 · 전화번호 필수. 실제 번호가 아니게 010-0000-xxxx 형식으로 만든다.
        String phone = String.format(Locale.ROOT, "0100000%04d", index % 10000);
        return new Customer(index, "guest", "가상손님" + (index + 1), phone, gender, ageGroup);
    }

    /** 손님 한 명의 학기 중 예약 수: 1 ~ 8건, 2 ~ 4건이 많다. */
    private int reservationsPerCustomer() {
        return 1 + pickIndex(new double[] {0.18, 0.22, 0.2, 0.15, 0.1, 0.07, 0.05, 0.03});
    }

    // ---- 예약 ----

    private Reservation reservation(Customer customer, Map<String, Integer> teamsPerSlot, Set<String> reservationNos) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            LocalDate day = day();
            SeedRegionData.Store store = store(day);
            if (store.closedDays().contains(dayCode(day.getDayOfWeek()))) {
                continue;
            }
            LocalTime start = slot(store);
            if (start == null) {
                continue;
            }
            String slotKey = store.code() + "@" + day + "T" + start;
            int teams = teamsPerSlot.getOrDefault(slotKey, 0);
            if (teams >= store.capacityPerSlot()) {
                continue;
            }
            teamsPerSlot.put(slotKey, teams + 1);
            Instant startAt = LocalDateTime.of(day, start).atZone(SERVICE_ZONE).toInstant();
            return outcome(customer, store, startAt, partySize(store), reservationNo(reservationNos));
        }
        return null;
    }

    /** 요일 비중: 주말 · 금요일이 많다. */
    private LocalDate day() {
        long days = ChronoUnit.DAYS.between(PERIOD_START, PERIOD_END) + 1;
        while (true) {
            LocalDate day = PERIOD_START.plusDays(random.nextInt((int) days));
            double weight = switch (day.getDayOfWeek()) {
                case FRIDAY -> 1.4;
                case SATURDAY -> 1.3;
                case THURSDAY -> 1.1;
                case SUNDAY -> 0.8;
                default -> 0.9;
            };
            if (random.nextDouble() * 1.4 < weight) {
                return day;
            }
        }
    }

    /** 가게 고르기: 업종 비중, 금요일 주점은 크게(NFR-08 패턴 2), 주말 공방은 조금 더. */
    private SeedRegionData.Store store(LocalDate day) {
        double[] weights = new double[stores.size()];
        for (int i = 0; i < stores.size(); i++) {
            weights[i] = storeWeight(stores.get(i).category(), day.getDayOfWeek());
        }
        return stores.get(pickIndex(weights));
    }

    static double storeWeight(SeedCategory category, DayOfWeek day) {
        double base = switch (category) {
            case RESTAURANT -> 1.3;
            case CAFE -> 1.2;
            case DESSERT -> 1.0;
            case PUB -> 0.7;
            case WORKSHOP -> 0.5;
            case BOOKSTORE, GIFT_SHOP, STATIONERY -> 0.4;
        };
        if (category == SeedCategory.PUB) {
            base *= switch (day) {
                case FRIDAY -> 4.0;
                case SATURDAY -> 1.8;
                case THURSDAY -> 1.3;
                default -> 1.0;
            };
        }
        if (category == SeedCategory.WORKSHOP && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)) {
            base *= 1.6;
        }
        return base;
    }

    /** 영업시간 안의 슬롯 시작 시각을 업종별 시간 분포로 고른다. 마지막 슬롯은 영업 종료까지 끝나야 한다(D5). */
    private LocalTime slot(SeedRegionData.Store store) {
        List<LocalTime> starts = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        LocalTime t = store.openTime();
        while (!t.plusMinutes(store.slotMinutes()).isAfter(store.closeTime())
                && t.plusMinutes(store.slotMinutes()).isAfter(t)) {
            starts.add(t);
            weights.add(hourWeight(store.category(), t.getHour()));
            t = t.plusMinutes(store.slotMinutes());
        }
        if (starts.isEmpty()) {
            return null;
        }
        return starts.get(pickIndex(weights.stream().mapToDouble(Double::doubleValue).toArray()));
    }

    static double hourWeight(SeedCategory category, int hour) {
        return switch (category) {
            case CAFE -> hour >= 13 && hour < 17 ? 2.0 : hour >= 10 && hour < 19 ? 1.0 : 0.3;
            case DESSERT -> hour >= 13 && hour < 18 ? 2.0 : hour >= 18 && hour < 21 ? 1.5 : 0.5;
            case RESTAURANT -> (hour >= 11 && hour < 13) || (hour >= 17 && hour < 20) ? 2.5 : 0.3;
            case PUB -> hour >= 18 && hour < 21 ? 2.0 : hour >= 21 ? 1.0 : 0.3;
            case WORKSHOP -> hour >= 13 && hour < 18 ? 1.0 : 0.5;
            case BOOKSTORE, GIFT_SHOP, STATIONERY -> hour >= 11 && hour < 19 ? 1.0 : 0.3;
        };
    }

    private int partySize(SeedRegionData.Store store) {
        int size = switch (store.category()) {
            case PUB -> 2 + pickIndex(new double[] {0.3, 0.3, 0.2, 0.12, 0.08});
            case RESTAURANT -> 1 + pickIndex(new double[] {0.2, 0.45, 0.2, 0.15});
            case CAFE, DESSERT -> 1 + pickIndex(new double[] {0.35, 0.45, 0.2});
            case WORKSHOP -> 1 + pickIndex(new double[] {0.35, 0.45, 0.12, 0.08});
            case BOOKSTORE, GIFT_SHOP, STATIONERY -> 1 + pickIndex(new double[] {0.7, 0.3});
        };
        return store.maxPartySize() == null ? size : Math.min(size, store.maxPartySize());
    }

    // ---- 취소 · 방문 ----

    private Reservation outcome(Customer customer, SeedRegionData.Store store, Instant startAt, int party, String no) {
        // 신청은 예약 시각 1시간 ~ 예약 가능 기간(최대 7일) 전
        long maxLeadMinutes = Duration.ofDays(Math.min(7, store.bookingWindowDays())).toMinutes();
        Instant requestedAt = startAt.minus(between(60, maxLeadMinutes), ChronoUnit.MINUTES);
        int deposit = store.depositPerPerson() * party;
        double roll = random.nextDouble();

        if (roll < STORE_REJECTED) {
            Instant canceledAt = earlierOf(requestedAt.plus(between(10, 180), ChronoUnit.MINUTES), startAt);
            return new Reservation(no, customer.index(), store.code(), startAt, party, requestedAt, "canceled",
                    "store_rejected", null, null, canceledAt, null, deposit);
        }
        Instant confirmedAt = earlierOf(requestedAt.plus(between(5, 120), ChronoUnit.MINUTES), startAt);
        if (roll < STORE_REJECTED + CANCEL_DAY_BEFORE + CANCEL_SAME_DAY) {
            boolean dayBefore = roll < STORE_REJECTED + CANCEL_DAY_BEFORE
                    && Duration.between(confirmedAt, startAt).toHours() >= 24;
            Instant canceledAt = dayBefore
                    ? latestBetween(confirmedAt, startAt.minus(Duration.ofHours(24)))
                    : latestBetween(confirmedAt, startAt.minus(Duration.ofMinutes(between(30, 600))));
            return new Reservation(no, customer.index(), store.code(), startAt, party, requestedAt, "canceled",
                    "customer_canceled", confirmedAt, null, canceledAt, null, deposit);
        }
        Instant visitedAt = startAt.plus(between(0, 15), ChronoUnit.MINUTES);
        return new Reservation(no, customer.index(), store.code(), startAt, party, requestedAt, "visited", null,
                confirmedAt, visitedAt, null, visitAmount(store.category(), party), deposit);
    }

    /** 방문 금액(쿠폰 할인 전, 원): 업종별 1인 금액 × 인원, 100원 단위. 소품 · 서점 · 문구는 한 팀 금액. */
    private int visitAmount(SeedCategory category, int party) {
        int amount = switch (category) {
            case RESTAURANT -> between(9000, 15000) * party;
            case CAFE -> between(5000, 8000) * party;
            case DESSERT -> between(6000, 11000) * party;
            case PUB -> between(15000, 25000) * party;
            case WORKSHOP -> between(30000, 45000) * party;
            case BOOKSTORE -> between(12000, 25000);
            case GIFT_SHOP -> between(8000, 22000);
            case STATIONERY -> between(3000, 10000);
        };
        return amount / 100 * 100;
    }

    /** from보다 뒤이고 at 이하인 시각. at이 from보다 이르면 from 직후. */
    private Instant latestBetween(Instant from, Instant at) {
        return at.isAfter(from) ? at : from.plus(1, ChronoUnit.MINUTES);
    }

    private static Instant earlierOf(Instant a, Instant b) {
        return a.isBefore(b) ? a : b.minus(1, ChronoUnit.MINUTES);
    }

    private String reservationNo(Set<String> used) {
        while (true) {
            StringBuilder no = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                no.append(RESERVATION_NO_CHARS.charAt(random.nextInt(RESERVATION_NO_CHARS.length())));
            }
            if (used.add(no.toString())) {
                return no.toString();
            }
        }
    }

    // ---- 도우미 ----

    static String dayCode(DayOfWeek day) {
        return day.name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private long between(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min + 1));
    }

    private <T> T pick(T[] values, double[] weights) {
        return values[pickIndex(weights)];
    }

    private int pickIndex(double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }
}
