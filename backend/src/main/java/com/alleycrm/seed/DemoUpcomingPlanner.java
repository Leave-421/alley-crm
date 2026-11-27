package com.alleycrm.seed;

import com.alleycrm.seed.DemoReservationPlanner.Customer;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 다가올 예약 · 방문 신고(#315): 시드를 돌린 시각(서버 시계) 기준으로 사장님 화면(승인 · 방문 처리 · 변경 · 지연 · 신고)을
 * 바로 시연할 수 있게 한다. DB 없이 계획만 만든다. 같은 seed · 같은 시각이면 같은 결과다.
 *
 * <ul>
 *   <li>예약: 오늘 ~ 6일 뒤, 지금보다 늦은 슬롯만. 가게 영업시간 · 휴무일 · 슬롯 · 슬롯당 팀 수(지난 기록 포함) · 예약 가능 기간 ·
 *       최대 인원을 지킨다. 신청 · 확정 시각은 지금보다 앞이다</li>
 *   <li>상태: 신청(확정 시각 없음)과 확정. 가게마다 신청 · 확정을 하나 이상, 오늘 남은 시간에 확정 몇 건</li>
 *   <li>변경 신청(D8): 확정됐다가 바꿀 값이 대기 중인 예약. 상태 '신청' · 확정 시각 있음 · 바꿀 시각(다른 빈 슬롯)과 인원.
 *       원래 시각 · 바꿀 시각이 모두 미래라 자동 거절(D7, 둘 중 이른 쪽)에 바로 걸리지 않는다</li>
 *   <li>지연 입력(D14): 오늘 확정 예약 몇 건에 예상 도착 = 예약 시각 + 10 ~ 30분</li>
 *   <li>방문 신고(FR-STL-08): 최근 7일 방문 처리된 회원 예약의 금액 신고(접수 · 사장님 확인 요청). 정산 확정된 달은 빼고,
 *       신고 시각은 방문 처리 뒤 · 지금 전</li>
 * </ul>
 * 예약금은 지난 기록과 같이 예약 칸에만 남기고 가상 잔액 거래는 만들지 않는다. 소개 · 쿠폰 · 알림은 만들지 않는다.
 * 미처리 신고('방문했어요')는 자동 취소된 예약에만 받는데 지난 기록에 자동 취소가 없어 만들지 않는다.
 */
public final class DemoUpcomingPlanner {

    /** 다가올 예약을 만드는 날 수(오늘 포함). */
    static final int UPCOMING_DAYS = 7;
    /** 다가올 예약 목표 수(권역 전체). 가게마다 신청 · 확정을 채운 뒤 모자라면 더한다. */
    static final int TARGET_RESERVATIONS = 36;
    /** 오늘 남은 시간의 확정 예약 수(방문 처리 · 지연 시연). */
    static final int TODAY_CONFIRMED = 4;
    /** 오늘 확정 예약 중 지연 입력 수. */
    static final int DELAYS = 2;
    /** 변경 신청 대기 수. */
    static final int CHANGE_REQUESTS = 4;
    /** 변경 신청 원래 예약은 오늘 + 이 날 수 이후(신청할 때 가게 취소 마감 · 24시간 전 기준을 넉넉히 넘김). */
    static final int CHANGE_MIN_DAYS_AHEAD = 2;
    /** 방문 신고 수(사장님 확인 요청 · 접수 반반). */
    static final int REPORTS = 4;
    /** 방문 신고 대상: 최근 며칠 안 방문. */
    static final int REPORT_LOOKBACK_DAYS = 7;
    /** 신청은 지금보다 최대 이만큼 앞. */
    static final Duration MAX_REQUEST_LEAD = Duration.ofHours(72);
    private static final int EXTRA_ATTEMPTS = 200;
    private static final int CUSTOMER_ATTEMPTS = 50;

    static final String REQUESTED = "requested";
    static final String CONFIRMED = "confirmed";
    static final String REPORT_AMOUNT = "amount";
    static final String REPORT_RECEIVED = "received";
    static final String REPORT_OWNER_CHECK_REQUESTED = "owner_check_requested";

    /**
     * 다가올 예약 한 건. 변경 신청이면 {@code pendingStartAt}이 있고 상태는 신청, 확정 시각은 원래 확정 때 값이다.
     *
     * @param delayEta 지연 입력 예상 도착(없으면 null)
     */
    public record Upcoming(String reservationNo, int customerIndex, String storeCode, Instant startAt, int partySize,
            Instant requestedAt, String status, Instant confirmedAt, int depositAmount, Instant pendingStartAt,
            Integer pendingPartySize, Instant delayEta) {

        public boolean changeRequested() {
            return pendingStartAt != null;
        }
    }

    /** 방문 신고 한 건(지난 기록의 방문 처리 예약에). */
    public record Report(String reservationNo, int customerIndex, String type, Integer claimedAmount, String status,
            Instant createdAt) {
    }

    public record Plan(List<Upcoming> reservations, List<Report> reports) {
    }

    private final List<SeedRegionData.Store> stores;
    private final DemoReservationPlanner.Plan history;
    private final DemoPeriod period;
    private final Instant now;
    private final Random random;
    private final Map<String, Integer> teamsPerSlot = new HashMap<>();
    private final Set<String> reservationNos = new HashSet<>();
    /** 손님 @ 날짜: 한 손님은 하루에 다가올 예약 하나만. */
    private final Set<String> customerDays = new HashSet<>();

    private DemoUpcomingPlanner(List<SeedRegionData.Store> stores, DemoReservationPlanner.Plan history, Instant now,
            long seed) {
        this.stores = stores;
        this.history = history;
        this.period = history.period();
        this.now = now;
        this.random = new Random(seed);
    }

    /**
     * @param history 지난 기록(슬롯 자리 · 예약번호 · 손님 · 신고 대상)
     * @param now 시드를 돌린 시각. 운영 시간대 날짜가 기간의 오늘이어야 한다
     */
    public static Plan plan(List<SeedRegionData.Store> stores, DemoReservationPlanner.Plan history, Instant now, long seed) {
        if (!LocalDate.ofInstant(now, DemoReservationPlanner.SERVICE_ZONE).equals(history.period().today())) {
            throw new IllegalArgumentException("지금 시각이 가상 데이터 기간의 오늘이 아닙니다: " + now + ", 오늘 "
                    + history.period().today());
        }
        return new DemoUpcomingPlanner(stores, history, now, seed).build();
    }

    private Plan build() {
        Map<String, SeedRegionData.Store> byCode = new HashMap<>();
        stores.forEach(s -> byCode.put(s.code(), s));
        for (Reservation r : history.reservations()) {
            reservationNos.add(r.reservationNo());
            teamsPerSlot.merge(DemoReservationPlanner.slotKey(byCode.get(r.storeCode()),
                    LocalDateTime.ofInstant(r.startAt(), DemoReservationPlanner.SERVICE_ZONE)), 1, Integer::sum);
        }
        List<Upcoming> upcoming = new ArrayList<>();
        List<SeedRegionData.Store> ownerFirst = ownerStoreFirst();

        // ① 오늘 남은 시간의 확정 예약. 앞의 몇 건은 지연 입력
        int today = 0;
        for (SeedRegionData.Store store : ownerFirst) {
            if (today >= TODAY_CONFIRMED) {
                break;
            }
            LocalDateTime slot = freeSlot(store, period.today(), period.today(), null);
            if (slot != null) {
                upcoming.add(book(store, slot, true, today < DELAYS));
                today++;
            }
        }
        // ② 가게마다 확정 · 신청을 하나 이상
        for (SeedRegionData.Store store : stores) {
            if (upcoming.stream().noneMatch(u -> u.storeCode().equals(store.code()) && CONFIRMED.equals(u.status()))) {
                addIfFree(upcoming, store, period.today(), true);
            }
            addIfFree(upcoming, store, period.today(), false);
        }
        // ③ 변경 신청 대기
        int changes = 0;
        for (SeedRegionData.Store store : ownerFirst) {
            if (changes >= CHANGE_REQUESTS) {
                break;
            }
            Upcoming change = changeRequest(store);
            if (change != null) {
                upcoming.add(change);
                changes++;
            }
        }
        // ④ 목표 수까지 임의 가게 · 날짜
        for (int attempt = 0; attempt < EXTRA_ATTEMPTS && upcoming.size() < TARGET_RESERVATIONS; attempt++) {
            addIfFree(upcoming, stores.get(random.nextInt(stores.size())), period.today(), random.nextBoolean());
        }
        return new Plan(upcoming, reports());
    }

    /** 시연 사장님 계정의 가게를 맨 앞에, 나머지는 섞은 순서. 그 가게 화면에 지연 · 변경 · 신고가 보이게 한다. */
    private List<SeedRegionData.Store> ownerStoreFirst() {
        List<SeedRegionData.Store> others = new ArrayList<>(stores.stream()
                .filter(s -> !s.code().equals(DemoAccountSeeder.OWNER_STORE_CODE)).toList());
        Collections.shuffle(others, random);
        List<SeedRegionData.Store> ordered = new ArrayList<>();
        stores.stream().filter(s -> s.code().equals(DemoAccountSeeder.OWNER_STORE_CODE)).forEach(ordered::add);
        ordered.addAll(others);
        return ordered;
    }

    private void addIfFree(List<Upcoming> upcoming, SeedRegionData.Store store, LocalDate from, boolean confirmed) {
        LocalDateTime slot = freeSlot(store, from, lastDay(store), null);
        if (slot != null) {
            upcoming.add(book(store, slot, confirmed, false));
        }
    }

    /** 다가올 예약의 마지막 날: 오늘 + 6일과 예약 가능 기간 중 이른 날. */
    private LocalDate lastDay(SeedRegionData.Store store) {
        return period.today().plusDays(Math.min(UPCOMING_DAYS - 1, store.bookingWindowDays()));
    }

    /**
     * [from, to] 날짜의 빈 슬롯 하나(업종별 시간 분포). 지금보다 늦고, 휴무일이 아니고, 슬롯당 팀 수가 남은 것. 없으면 null.
     *
     * @param except 고르지 않을 슬롯(변경 신청의 원래 시각)
     */
    private LocalDateTime freeSlot(SeedRegionData.Store store, LocalDate from, LocalDate to, LocalDateTime except) {
        List<LocalDateTime> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (store.closedDays().contains(DemoReservationPlanner.dayCode(day.getDayOfWeek()))) {
                continue;
            }
            for (LocalTime t : DemoReservationPlanner.slotStarts(store)) {
                LocalDateTime at = LocalDateTime.of(day, t);
                if (!instant(at).isAfter(now) || at.equals(except) || !hasRoom(store, at)) {
                    continue;
                }
                candidates.add(at);
                weights.add(DemoReservationPlanner.hourWeight(store.category(), t.getHour()));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(pickIndex(weights));
    }

    private boolean hasRoom(SeedRegionData.Store store, LocalDateTime at) {
        return teamsPerSlot.getOrDefault(DemoReservationPlanner.slotKey(store, at), 0) < store.capacityPerSlot();
    }

    /** 신청(또는 확정) 예약 한 건. 신청 · 확정 시각은 지금보다 앞이고, 신청한 날 기준 예약 가능 기간 안이다. */
    private Upcoming book(SeedRegionData.Store store, LocalDateTime start, boolean confirmed, boolean delayed) {
        teamsPerSlot.merge(DemoReservationPlanner.slotKey(store, start), 1, Integer::sum);
        int party = partySize(store);
        Instant startAt = instant(start);
        Instant requestedAt = requestedAt(store, start.toLocalDate());
        Instant confirmedAt = confirmed ? confirmedAt(requestedAt) : null;
        Instant delayEta = delayed ? startAt.plus(between(10, 30), ChronoUnit.MINUTES) : null;
        return new Upcoming(reservationNo(), customer(start.toLocalDate()), store.code(), startAt, party, requestedAt,
                confirmed ? CONFIRMED : REQUESTED, confirmedAt, store.depositPerPerson() * party, null, null, delayEta);
    }

    /**
     * 변경 신청(D8): 원래는 확정 예약(오늘 + 2일 이후), 바꿀 시각은 같은 가게의 다른 빈 슬롯. 인원은 예약금이 없는 가게만 바꾼다
     * — 예약금이 있는 가게에서 인원이 늘면 승인 때 회원 잔액에서 차액을 빼는데, 지난 기록처럼 가상 잔액 거래를 만들지 않으므로
     * 잔액이 모자라 승인이 실패할 수 있다. 바꿀 슬롯도 자리를 하나 차지한 것으로 센다. 못 만들면 null.
     */
    private Upcoming changeRequest(SeedRegionData.Store store) {
        LocalDate from = period.today().plusDays(CHANGE_MIN_DAYS_AHEAD);
        LocalDate to = lastDay(store);
        LocalDateTime original = freeSlot(store, from, to, null);
        if (original == null) {
            return null;
        }
        teamsPerSlot.merge(DemoReservationPlanner.slotKey(store, original), 1, Integer::sum);
        LocalDateTime pending = freeSlot(store, period.today().plusDays(1), to, original);
        if (pending == null) {
            teamsPerSlot.merge(DemoReservationPlanner.slotKey(store, original), -1, Integer::sum);
            return null;
        }
        teamsPerSlot.merge(DemoReservationPlanner.slotKey(store, pending), 1, Integer::sum);
        int party = partySize(store);
        int pendingParty = party;
        if (store.depositPerPerson() == 0 && random.nextBoolean()) {
            int max = store.maxPartySize() == null ? party + 1 : store.maxPartySize();
            pendingParty = party < max ? party + 1 : Math.max(1, party - 1);
        }
        Instant requestedAt = requestedAt(store, original.toLocalDate());
        return new Upcoming(reservationNo(), customer(original.toLocalDate()), store.code(), instant(original), party,
                requestedAt, REQUESTED, confirmedAt(requestedAt), store.depositPerPerson() * party, instant(pending),
                pendingParty, null);
    }

    /** 지금보다 1분 ~ 72시간 앞(분 단위). 예약일 − 예약 가능 기간 날짜 00:00보다 앞서지 않는다. */
    private Instant requestedAt(SeedRegionData.Store store, LocalDate startDay) {
        Instant base = now.truncatedTo(ChronoUnit.MINUTES);
        Instant earliest = startDay.minusDays(store.bookingWindowDays()).atStartOfDay(DemoReservationPlanner.SERVICE_ZONE)
                .toInstant();
        long maxLead = Math.max(2, Math.min(MAX_REQUEST_LEAD.toMinutes(), Duration.between(earliest, base).toMinutes()));
        long lead = between(Math.min(30, maxLead), maxLead);
        return base.minus(lead, ChronoUnit.MINUTES);
    }

    /** 신청 뒤 1 ~ 120분, 지금보다 앞. */
    private Instant confirmedAt(Instant requestedAt) {
        long room = Duration.between(requestedAt, now.truncatedTo(ChronoUnit.MINUTES)).toMinutes() - 1;
        return requestedAt.plus(between(1, (int) Math.max(1, Math.min(120, room))), ChronoUnit.MINUTES);
    }

    /** 그날 다가올 예약이 아직 없는 손님(못 찾으면 아무 손님). */
    private int customer(LocalDate day) {
        List<Customer> customers = history.customers();
        for (int attempt = 0; attempt < CUSTOMER_ATTEMPTS; attempt++) {
            Customer c = customers.get(random.nextInt(customers.size()));
            if (customerDays.add(c.index() + "@" + day)) {
                return c.index();
            }
        }
        return customers.get(random.nextInt(customers.size())).index();
    }

    /**
     * 방문 신고: 최근 7일 방문 처리된 회원 예약 중 정산 확정 전 달의 것. 시연 사장님 가게 예약이 있으면 사장님 확인 요청 하나를 그
     * 가게에 둔다(사장님은 확인 요청받은 신고만 본다).
     */
    private List<Report> reports() {
        Instant from = period.today().minusDays(REPORT_LOOKBACK_DAYS).atStartOfDay(DemoReservationPlanner.SERVICE_ZONE)
                .toInstant();
        YearMonth lastConfirmed = period.lastConfirmedMonth();
        List<Reservation> candidates = new ArrayList<>(history.reservations().stream()
                .filter(r -> "visited".equals(r.status()))
                .filter(r -> "member".equals(history.customers().get(r.customerIndex()).type()))
                .filter(r -> !r.visitedAt().isBefore(from))
                .filter(r -> YearMonth.from(r.startAt().atZone(DemoReservationPlanner.SERVICE_ZONE)).isAfter(lastConfirmed))
                .filter(r -> Duration.between(r.visitedAt(), now).toMinutes() > 20)
                .toList());
        Collections.shuffle(candidates, random);
        candidates.stream().filter(r -> r.storeCode().equals(DemoAccountSeeder.OWNER_STORE_CODE)).findFirst()
                .ifPresent(r -> {
                    candidates.remove(r);
                    candidates.add(0, r);
                });
        List<Report> reports = new ArrayList<>();
        for (Reservation r : candidates.subList(0, Math.min(REPORTS, candidates.size()))) {
            long span = Duration.between(r.visitedAt(), now).toMinutes();
            Instant createdAt = r.visitedAt().plus(between(10, span - 1), ChronoUnit.MINUTES);
            Integer claimed = random.nextDouble() < 0.7 ? Math.max(0, r.visitAmount() - between(1, 5) * 1000) : null;
            String status = reports.size() < REPORTS / 2 ? REPORT_OWNER_CHECK_REQUESTED : REPORT_RECEIVED;
            reports.add(new Report(r.reservationNo(), r.customerIndex(), REPORT_AMOUNT, claimed, status, createdAt));
        }
        return reports;
    }

    private int partySize(SeedRegionData.Store store) {
        int size = switch (store.category()) {
            case PUB -> between(2, 5);
            case RESTAURANT, WORKSHOP -> between(1, 4);
            case CAFE, DESSERT -> between(1, 3);
            case BOOKSTORE, GIFT_SHOP, STATIONERY -> between(1, 2);
        };
        return store.maxPartySize() == null ? size : Math.min(size, store.maxPartySize());
    }

    private String reservationNo() {
        String chars = DemoReservationPlanner.RESERVATION_NO_CHARS;
        while (true) {
            StringBuilder no = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                no.append(chars.charAt(random.nextInt(chars.length())));
            }
            if (reservationNos.add(no.toString())) {
                return no.toString();
            }
        }
    }

    private static Instant instant(LocalDateTime local) {
        return local.atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private long between(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min + 1));
    }

    private int pickIndex(List<Double> weights) {
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.size(); i++) {
            r -= weights.get(i);
            if (r < 0) {
                return i;
            }
        }
        return weights.size() - 1;
    }
}
