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
 *   <li>예약: 어제까지 91일({@link DemoPeriod}, 운영 시간대), 업종별 시간 분포, 가게 영업시간 · 휴무일 · 슬롯 · 슬롯당 팀 수 · 최대 인원을 지킨다</li>
 *   <li>금요일 저녁 주점 예약 집중(NFR-08 패턴 2)</li>
 *   <li>취소 10% 안팎: 하루 전 · 당일 손님 취소, 가게 거절(NFR-08)</li>
 *   <li>나머지는 방문 처리, 방문 금액(쿠폰 할인 전)을 업종 · 인원으로 만든다</li>
 * </ul>
 * 그 뒤 가상 데이터 2의 6단계(E17 #262, 같은 날 연속 방문)를 같은 계획에 더한다:
 * <ul>
 *   <li>20대 카페 → 디저트(NFR-08 패턴 1): 카페 방문 뒤 같은 날 바로 다음 방문으로 디저트를 20대에게 훨씬 자주</li>
 *   <li>3곳 이어 방문: 오늘의 코스(FR-CRS-11)가 보이도록 평일 · 주말 × 점심 · 오후 · 저녁 칸마다 같은 동선을 5명 이상</li>
 * </ul>
 * 이어서 8 · 9단계(E17 #268): 회원의 방문한 예약에서 정식 코스 제안 → '다음 코스로 예약하기'(마감 안이면 소개 후보, 지나면
 * 코스 연결만) → 소개로 방문한 손님의 재방문(NFR-08 패턴 3). 노출 · 수수료 · 정산은 적재기가 이 결과로 만든다.
 * 쿠폰 사례는 E17의 다음 Task가 더한다.
 */
public final class DemoReservationPlanner {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /** 예약번호 글자(CONTRACTS_API 「예약 응답」: 헷갈리는 0 O 1 I 제외). */
    static final String RESERVATION_NO_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
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

    /**
     * @param proposals 정식 코스 제안(E17)
     * @param courseLinks '다음 코스로 예약하기'로 만든 예약의 연결(E17)
     * @param period 이 계획의 기간(뒤 계획들이 같은 기간을 쓴다)
     */
    public record Plan(List<Customer> customers, List<Reservation> reservations, List<Proposal> proposals,
            List<CourseLink> courseLinks, DemoPeriod period) {
    }

    private final List<SeedRegionData.Store> stores;
    private final DemoPeriod period;
    private final Random random;

    private DemoReservationPlanner(List<SeedRegionData.Store> stores, DemoPeriod period, long seed) {
        this.stores = stores;
        this.period = period;
        this.random = new Random(seed);
    }

    /** 같은 seed · 같은 기간이면 같은 계획이다. */
    public static Plan plan(List<SeedRegionData.Store> stores, DemoPeriod period, int customerCount, long seed) {
        if (customerCount < 1) {
            throw new IllegalArgumentException("가상 손님 수는 1 이상이어야 합니다: " + customerCount);
        }
        return new DemoReservationPlanner(stores, period, seed).build(customerCount);
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
        new SameDayVisits(customers, reservations, teamsPerSlot, reservationNos).add();
        List<Proposal> proposals = new ArrayList<>();
        List<CourseLink> links = new ArrayList<>();
        new CourseFlows(customers, reservations, teamsPerSlot, reservationNos, proposals, links).add();
        return new Plan(customers, reservations, proposals, links, period);
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
        while (true) {
            LocalDate day = period.start().plusDays(random.nextInt(period.days()));
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
        return outcome(customer, store, startAt, party, no, requestedAt);
    }

    /** 신청 시각이 정해진 예약의 결과(가게 거절 · 손님 취소 · 방문). 코스 예약은 제안을 본 뒤 신청 시각을 정한다(E17). */
    private Reservation outcome(Customer customer, SeedRegionData.Store store, Instant startAt, int party, String no,
            Instant requestedAt) {
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

    // ---- 같은 날 연속 방문 (E17, 06-data 「가상 데이터 생성」 6단계) ----

    /** 20대 손님의 카페 방문 뒤 같은 날 디저트를 이어 갈 비율(NFR-08 패턴 1). */
    static final double CAFE_TO_DESSERT_TWENTIES = 0.5;
    /** 다른 연령대 · 연령대 모름의 비율. 20대와 차이가 패턴이 된다. */
    static final double CAFE_TO_DESSERT_OTHERS = 0.06;
    /** 이어 갈 다음 가게 슬롯을 찾는 범위(앞 방문이 끝난 뒤). */
    static final Duration FOLLOW_UP_WINDOW = Duration.ofHours(3);
    /** 오늘의 코스 동선마다 만들 고유 손님 수(작은 묶음 숨김 기준 5명 이상, FR-CRS-11). */
    static final int COURSE_MIN_CUSTOMERS = 5;
    static final int COURSE_MAX_CUSTOMERS = 12;
    private static final int COURSE_ATTEMPTS = 600;

    /**
     * 오늘의 코스 동선 틀: 요일 구분 · 첫 방문 시각대 · 가게 3곳(시드 코드). 칸마다 첫 가게가 다른 동선을 둔다.
     * 시각대는 추천 시간대 구간(점심 11 ~ 14 · 오후 14 ~ 17 · 저녁 17 ~ 21)과 같다.
     */
    record CourseTemplate(boolean weekend, int fromHour, int toHour, List<String> storeCodes) {
    }

    static final List<CourseTemplate> COURSES = List.of(
            new CourseTemplate(false, 11, 14, List.of("S03", "S05", "S08")),
            new CourseTemplate(false, 11, 14, List.of("S06", "S04", "S01")),
            new CourseTemplate(false, 14, 17, List.of("S01", "S08", "S10")),
            new CourseTemplate(false, 14, 17, List.of("S04", "S02", "S05")),
            new CourseTemplate(false, 17, 21, List.of("S03", "S08", "S11")),
            new CourseTemplate(false, 17, 21, List.of("S12", "S07", "S14")),
            new CourseTemplate(true, 11, 14, List.of("S06", "S05", "S08")),
            new CourseTemplate(true, 11, 14, List.of("S09", "S10", "S07")),
            new CourseTemplate(true, 14, 17, List.of("S15", "S08", "S10")),
            new CourseTemplate(true, 14, 17, List.of("S01", "S08", "S04")),
            new CourseTemplate(true, 17, 21, List.of("S12", "S08", "S11")),
            new CourseTemplate(true, 17, 21, List.of("S09", "S07", "S14")));

    /** 기존 계획에 같은 날 이어 방문을 더한다. 손님 · 날짜별로 이미 있는 예약을 보고, 사이에 다른 방문이 끼지 않게 한다. */
    private final class SameDayVisits {

        private final List<Customer> customers;
        private final List<Reservation> reservations;
        private final Map<String, Integer> teamsPerSlot;
        private final Set<String> reservationNos;
        private final Map<String, SeedRegionData.Store> byCode = new HashMap<>();
        /** 손님 · 운영 시간대 날짜 → 그날 예약 시작 시각들. */
        private final Map<String, List<Instant>> busy = new HashMap<>();

        SameDayVisits(List<Customer> customers, List<Reservation> reservations, Map<String, Integer> teamsPerSlot,
                Set<String> reservationNos) {
            this.customers = customers;
            this.reservations = reservations;
            this.teamsPerSlot = teamsPerSlot;
            this.reservationNos = reservationNos;
            stores.forEach(s -> byCode.put(s.code(), s));
            reservations.forEach(this::markBusy);
        }

        void add() {
            cafeToDessert();
            courses();
        }

        /** NFR-08 패턴 1: 방문한 카페 예약마다 연령대별 비율로 같은 날 바로 다음 방문을 디저트로. */
        private void cafeToDessert() {
            List<SeedRegionData.Store> desserts = stores.stream().filter(s -> s.category() == SeedCategory.DESSERT).toList();
            if (desserts.isEmpty()) {
                return;
            }
            for (Reservation cafe : List.copyOf(reservations)) {
                SeedRegionData.Store store = byCode.get(cafe.storeCode());
                if (!"visited".equals(cafe.status()) || store.category() != SeedCategory.CAFE) {
                    continue;
                }
                Customer customer = customers.get(cafe.customerIndex());
                double rate = "age_20_or_under".equals(customer.ageGroup()) ? CAFE_TO_DESSERT_TWENTIES : CAFE_TO_DESSERT_OTHERS;
                if (random.nextDouble() >= rate) {
                    continue;
                }
                SeedRegionData.Store dessert = desserts.get(random.nextInt(desserts.size()));
                Instant earliest = cafe.startAt().plus(Duration.ofMinutes(store.slotMinutes()));
                LocalTime next = nextFreeSlot(customer, dessert, cafe.startAt(), earliest);
                if (next != null) {
                    book(customer, dessert, LocalDateTime.of(localDate(cafe.startAt()), next));
                }
            }
        }

        /** 오늘의 코스 동선마다 그날 다른 예약이 없는 손님 · 날을 골라 3곳을 이어 방문시킨다. 셋 다 방문한 손님 수를 센다. */
        private void courses() {
            for (CourseTemplate template : COURSES) {
                List<SeedRegionData.Store> route = template.storeCodes().stream().map(byCode::get).toList();
                if (route.contains(null)) {
                    continue;
                }
                int target = between(COURSE_MIN_CUSTOMERS, COURSE_MAX_CUSTOMERS);
                int done = 0;
                for (int attempt = 0; attempt < COURSE_ATTEMPTS && done < target; attempt++) {
                    Customer customer = customers.get(random.nextInt(customers.size()));
                    LocalDate day = dayOf(template.weekend());
                    if (busy.containsKey(busyKey(customer, day))) {
                        continue;
                    }
                    List<LocalDateTime> starts = routeStarts(route, day, template);
                    if (starts == null) {
                        continue;
                    }
                    boolean allVisited = true;
                    for (int i = 0; i < route.size(); i++) {
                        allVisited &= "visited".equals(book(customer, route.get(i), starts.get(i)).status());
                    }
                    if (allVisited) {
                        done++;
                    }
                }
            }
        }

        /** 첫 가게는 그 시각대 안의 빈 슬롯, 다음 가게는 앞 방문이 끝난 뒤 가장 이른 빈 슬롯. 하나라도 없으면 null. */
        private List<LocalDateTime> routeStarts(List<SeedRegionData.Store> route, LocalDate day, CourseTemplate template) {
            for (SeedRegionData.Store store : route) {
                if (store.closedDays().contains(dayCode(day.getDayOfWeek()))) {
                    return null;
                }
            }
            List<LocalTime> firsts = slotStarts(route.get(0)).stream()
                    .filter(t -> t.getHour() >= template.fromHour() && t.getHour() < template.toHour())
                    .filter(t -> hasRoom(route.get(0), LocalDateTime.of(day, t)))
                    .toList();
            if (firsts.isEmpty()) {
                return null;
            }
            List<LocalDateTime> starts = new ArrayList<>();
            starts.add(LocalDateTime.of(day, firsts.get(random.nextInt(firsts.size()))));
            for (int i = 1; i < route.size(); i++) {
                SeedRegionData.Store store = route.get(i);
                LocalDateTime after = starts.get(i - 1).plusMinutes(route.get(i - 1).slotMinutes());
                LocalDateTime next = slotStarts(store).stream()
                        .map(t -> LocalDateTime.of(day, t))
                        .filter(at -> !at.isBefore(after) && at.isBefore(after.plus(FOLLOW_UP_WINDOW)))
                        .filter(at -> hasRoom(store, at))
                        .findFirst().orElse(null);
                if (next == null) {
                    return null;
                }
                starts.add(next);
            }
            return starts;
        }

        /**
         * 앞 방문(previousStart) 뒤 같은 날 이 가게의 가장 이른 빈 슬롯. 그 사이에 이 손님의 다른 예약이 있거나
         * 휴무일 · 범위 밖이면 null.
         */
        private LocalTime nextFreeSlot(Customer customer, SeedRegionData.Store store, Instant previousStart, Instant earliest) {
            LocalDate day = localDate(previousStart);
            if (store.closedDays().contains(dayCode(day.getDayOfWeek()))) {
                return null;
            }
            Instant latest = earliest.plus(FOLLOW_UP_WINDOW);
            for (LocalTime t : slotStarts(store)) {
                Instant at = LocalDateTime.of(day, t).atZone(SERVICE_ZONE).toInstant();
                if (at.isBefore(earliest) || !at.isBefore(latest) || !hasRoom(store, LocalDateTime.of(day, t))) {
                    continue;
                }
                boolean between = busy.getOrDefault(busyKey(customer, day), List.of()).stream()
                        .anyMatch(other -> other.isAfter(previousStart) && !other.isAfter(at));
                return between ? null : t;
            }
            return null;
        }

        /** 이어 방문 예약 하나. 취소 · 방문은 E16과 같은 비율로 정한다(취소 10% 안팎 유지). */
        private Reservation book(Customer customer, SeedRegionData.Store store, LocalDateTime start) {
            teamsPerSlot.merge(slotKey(store, start), 1, Integer::sum);
            Reservation r = outcome(customer, store, start.atZone(SERVICE_ZONE).toInstant(), partySize(store),
                    reservationNo(reservationNos));
            reservations.add(r);
            markBusy(r);
            return r;
        }

        private boolean hasRoom(SeedRegionData.Store store, LocalDateTime start) {
            return teamsPerSlot.getOrDefault(slotKey(store, start), 0) < store.capacityPerSlot();
        }

        /** 기간 안에서 평일 또는 주말 하루. */
        private LocalDate dayOf(boolean weekend) {
            while (true) {
                LocalDate day = period.start().plusDays(random.nextInt(period.days()));
                boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
                if (isWeekend == weekend) {
                    return day;
                }
            }
        }

        private void markBusy(Reservation r) {
            busy.computeIfAbsent(busyKey(customers.get(r.customerIndex()), localDate(r.startAt())), k -> new ArrayList<>())
                    .add(r.startAt());
        }

        private String busyKey(Customer customer, LocalDate day) {
            return customer.index() + "@" + day;
        }

        private LocalDate localDate(Instant at) {
            return LocalDateTime.ofInstant(at, SERVICE_ZONE).toLocalDate();
        }
    }

    /** E16 슬롯 키(가게 @ 날짜 T 시작). 슬롯당 팀 수를 센다. */
    static String slotKey(SeedRegionData.Store store, LocalDateTime start) {
        return store.code() + "@" + start.toLocalDate() + "T" + start.toLocalTime();
    }

    /** 영업시간 안의 슬롯 시작 시각들. 마지막 슬롯은 영업 종료까지 끝나야 한다(D5). */
    static List<LocalTime> slotStarts(SeedRegionData.Store store) {
        List<LocalTime> starts = new ArrayList<>();
        LocalTime t = store.openTime();
        while (!t.plusMinutes(store.slotMinutes()).isAfter(store.closeTime())
                && t.plusMinutes(store.slotMinutes()).isAfter(t)) {
            starts.add(t);
            t = t.plusMinutes(store.slotMinutes());
        }
        return starts;
    }

    // ---- 정식 코스 제안 · 코스 예약 · 재방문 (E17, 06-data 「가상 데이터 생성」 8 · 9단계, NFR-08 패턴 3) ----

    /** 회원의 방문한 예약 중 정식 코스 제안을 본 비율. */
    static final double COURSE_PROPOSAL_RATE = 0.15;
    /** 제안을 본 회원이 '다음 코스로 예약하기'로 예약한 비율. */
    static final double COURSE_BOOKING_RATE = 0.55;
    /** 코스 예약 중 기준 예약일 당일이 지나 신청한 비율(소개 아님, 코스 연결만 — D12). */
    static final double LATE_COURSE_REQUEST_RATE = 0.12;
    /** 코스 예약을 기준 예약과 같은 날로 잡는 비율(나머지는 1 ~ 4일 뒤). */
    static final double SAME_DAY_COURSE_RATE = 0.6;
    /** NFR-08 패턴 3: 소개로 방문한 손님이 그 가게를 다시 찾는 비율(시뮬레이션). */
    static final double COURSE_REVISIT_RATE = 0.6;
    /** 한 번에 보여 주는 제안 수(추천 개수 3). */
    static final int PROPOSALS_PER_PAGE = 3;

    /** 정식 코스 제안 한 건. (기준 예약, 추천 가게) 조합당 1건(uk_course_proposal). */
    public record Proposal(String baseReservationNo, String storeCode, Instant firstShownAt) {
    }

    /**
     * '다음 코스로 예약하기'로 만든 예약의 연결. 마감 안이면 제안 · 보낸 가게가 있고(소개 후보), 지나면 둘 다 null(코스 연결만).
     */
    public record CourseLink(String reservationNo, String rootReservationNo, String proposalStoreCode,
            String senderStoreCode) {

        public boolean referral() {
            return senderStoreCode != null;
        }
    }

    /**
     * 가게마다 같은 날 바로 다음에 많이 간 가게 순위(06-data 7 · 8단계). 05-policy 「다음 방문 추천」의 실제 기록 근거
     * (n_AB)를 근사한 것이고, 시드의 제안 · 노출 가게를 고르는 데만 쓴다. 기록이 모자라면 나머지 가게를 코드 순으로 채운다.
     */
    static Map<String, List<String>> nextStoreRanking(List<Customer> customers, List<Reservation> reservations,
            List<SeedRegionData.Store> stores) {
        Map<String, Map<String, Set<Integer>>> pairs = transitionCustomers(reservations);
        Map<String, List<String>> ranking = new HashMap<>();
        for (SeedRegionData.Store a : stores) {
            Map<String, Set<Integer>> next = pairs.getOrDefault(a.code(), Map.of());
            ranking.put(a.code(), stores.stream()
                    .map(SeedRegionData.Store::code)
                    .filter(code -> !code.equals(a.code()))
                    .sorted(java.util.Comparator.<String>comparingInt(code -> next.getOrDefault(code, Set.of()).size())
                            .reversed().thenComparing(code -> code))
                    .toList());
        }
        return ranking;
    }

    /** 가게 A → 같은 날 바로 다음에 간 가게 B → 그 손님들(방문 처리 예약, 운영 시간대 날짜). 05-policy n_AB의 원천. */
    static Map<String, Map<String, Set<Integer>>> transitionCustomers(List<Reservation> reservations) {
        Map<String, Map<String, Set<Integer>>> pairs = new HashMap<>();
        Map<String, List<Reservation>> days = new HashMap<>();
        reservations.stream()
                .filter(r -> "visited".equals(r.status()))
                .sorted(java.util.Comparator.comparing(Reservation::startAt))
                .forEach(r -> days.computeIfAbsent(r.customerIndex() + "@"
                        + LocalDateTime.ofInstant(r.startAt(), SERVICE_ZONE).toLocalDate(), k -> new ArrayList<>()).add(r));
        for (List<Reservation> day : days.values()) {
            for (int i = 0; i + 1 < day.size(); i++) {
                if (!day.get(i).storeCode().equals(day.get(i + 1).storeCode())) {
                    pairs.computeIfAbsent(day.get(i).storeCode(), k -> new HashMap<>())
                            .computeIfAbsent(day.get(i + 1).storeCode(), k -> new HashSet<>())
                            .add(day.get(i).customerIndex());
                }
            }
        }
        return pairs;
    }

    /** 방문한 회원 예약에서 정식 코스 제안 → 코스 예약 → (소개로 방문하면) 재방문을 더한다. */
    private final class CourseFlows {

        private final List<Customer> customers;
        private final List<Reservation> reservations;
        private final Map<String, Integer> teamsPerSlot;
        private final Set<String> reservationNos;
        private final List<Proposal> proposals;
        private final List<CourseLink> links;
        private final Map<String, SeedRegionData.Store> byCode = new HashMap<>();

        CourseFlows(List<Customer> customers, List<Reservation> reservations, Map<String, Integer> teamsPerSlot,
                Set<String> reservationNos, List<Proposal> proposals, List<CourseLink> links) {
            this.customers = customers;
            this.reservations = reservations;
            this.teamsPerSlot = teamsPerSlot;
            this.reservationNos = reservationNos;
            this.proposals = proposals;
            this.links = links;
            stores.forEach(s -> byCode.put(s.code(), s));
        }

        void add() {
            Map<String, List<String>> ranking = nextStoreRanking(customers, reservations, stores);
            for (Reservation base : List.copyOf(reservations)) {
                Customer customer = customers.get(base.customerIndex());
                if (!"visited".equals(base.status()) || !"member".equals(customer.type())
                        || random.nextDouble() >= COURSE_PROPOSAL_RATE) {
                    continue;
                }
                Instant deadline = deadline(base);
                // 제안은 기준 예약 신청 뒤에 표시한다(06-data 9단계)
                Instant shownAt = base.requestedAt().plus(between(1, 90), ChronoUnit.MINUTES);
                if (!shownAt.isBefore(deadline)) {
                    continue;
                }
                List<String> shown = ranking.get(base.storeCode()).subList(0, PROPOSALS_PER_PAGE);
                shown.forEach(code -> proposals.add(new Proposal(base.reservationNo(), code, shownAt)));
                if (random.nextDouble() >= COURSE_BOOKING_RATE) {
                    continue;
                }
                String chosen = shown.get(pickIndex(new double[] {0.5, 0.3, 0.2}));
                boolean late = random.nextDouble() < LATE_COURSE_REQUEST_RATE;
                Reservation course = book(customer, byCode.get(chosen), base, shownAt, deadline, late);
                if (course == null) {
                    continue;
                }
                CourseLink link = late
                        ? new CourseLink(course.reservationNo(), base.reservationNo(), null, null)
                        : new CourseLink(course.reservationNo(), base.reservationNo(), chosen, base.storeCode());
                links.add(link);
                if (link.referral() && "visited".equals(course.status()) && random.nextDouble() < COURSE_REVISIT_RATE) {
                    revisit(customer, byCode.get(chosen), course);
                }
            }
        }

        /**
         * 코스 예약. 신청 시각은 제안 표시 뒤 · 마감 전(소개 후보), late면 마감 뒤 1 ~ 48시간. 예약 시각은 기준 예약과 같은 날이면
         * 기준 시각보다 늦게(D12), 아니면 1 ~ 4일 뒤. 가게 규칙(휴무일 · 슬롯 · 팀 수 · 예약 가능 기간)을 지킨다. 못 잡으면 null.
         */
        private Reservation book(Customer customer, SeedRegionData.Store store, Reservation base, Instant shownAt,
                Instant deadline, boolean late) {
            Instant requestedAt = late
                    ? deadline.plus(between(60, 48 * 60), ChronoUnit.MINUTES)
                    : shownAt.plus(between(1, (int) Math.max(1, Math.min(240, Duration.between(shownAt, deadline).toMinutes() - 1))),
                            ChronoUnit.MINUTES);
            if (!late && !requestedAt.isBefore(deadline)) {
                return null;
            }
            LocalDate baseDay = LocalDateTime.ofInstant(base.startAt(), SERVICE_ZONE).toLocalDate();
            boolean sameDay = !late && random.nextDouble() < SAME_DAY_COURSE_RATE;
            LocalDate day = sameDay ? baseDay : baseDay.plusDays(between(1, 4));
            if (day.isAfter(period.end()) || store.closedDays().contains(dayCode(day.getDayOfWeek()))) {
                return null;
            }
            Instant earliest = sameDay ? base.startAt().plus(Duration.ofMinutes(1)) : requestedAt.plus(Duration.ofHours(1));
            Instant latest = requestedAt.plus(Duration.ofDays(Math.min(7, store.bookingWindowDays())));
            List<LocalTime> free = slotStarts(store).stream()
                    .filter(t -> {
                        Instant at = LocalDateTime.of(day, t).atZone(SERVICE_ZONE).toInstant();
                        return at.isAfter(earliest) && at.isAfter(requestedAt.plus(Duration.ofHours(1)))
                                && !at.isAfter(latest) && hasRoom(store, LocalDateTime.of(day, t));
                    })
                    .toList();
            if (free.isEmpty()) {
                return null;
            }
            LocalDateTime start = LocalDateTime.of(day, free.get(random.nextInt(Math.min(2, free.size()))));
            teamsPerSlot.merge(slotKey(store, start), 1, Integer::sum);
            Reservation r = outcome(customer, store, start.atZone(SERVICE_ZONE).toInstant(), partySize(store),
                    reservationNo(reservationNos), requestedAt);
            reservations.add(r);
            return r;
        }

        /** NFR-08 패턴 3: 소개로 방문한 가게를 10 ~ 45일 안에 다시 예약(기간 안일 때만). */
        private void revisit(Customer customer, SeedRegionData.Store store, Reservation course) {
            LocalDate day = LocalDateTime.ofInstant(course.startAt(), SERVICE_ZONE).toLocalDate().plusDays(between(10, 45));
            if (day.isAfter(period.end()) || store.closedDays().contains(dayCode(day.getDayOfWeek()))) {
                return;
            }
            List<LocalTime> free = slotStarts(store).stream().filter(t -> hasRoom(store, LocalDateTime.of(day, t))).toList();
            if (free.isEmpty()) {
                return;
            }
            LocalDateTime start = LocalDateTime.of(day, free.get(random.nextInt(free.size())));
            teamsPerSlot.merge(slotKey(store, start), 1, Integer::sum);
            reservations.add(outcome(customer, store, start.atZone(SERVICE_ZONE).toInstant(), partySize(store),
                    reservationNo(reservationNos)));
        }

        private boolean hasRoom(SeedRegionData.Store store, LocalDateTime start) {
            return teamsPerSlot.getOrDefault(slotKey(store, start), 0) < store.capacityPerSlot();
        }
    }

    /** 소개 예약 신청 마감: 기준 예약일(운영 시간대) 다음 날 00:00(05-policy 「소개 판정」). */
    static Instant deadline(Reservation base) {
        return LocalDateTime.ofInstant(base.startAt(), SERVICE_ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(SERVICE_ZONE).toInstant();
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
