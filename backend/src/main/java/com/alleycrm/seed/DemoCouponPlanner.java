package com.alleycrm.seed;

import com.alleycrm.seed.DemoReservationPlanner.CourseLink;
import com.alleycrm.seed.DemoReservationPlanner.Customer;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 가상 데이터 2의 쿠폰 시연 사례(E17 #279, 06-data 「가상 데이터 생성」, NFR-08 #5). DB 없이 계획만 만든다. 같은 seed면 같은 결과다.
 *
 * <p>가게 쿠폰 행사 4개(정액 · 정률 · 서비스 제공 · 중단한 행사)에 대해, 지도 추천 노출을 출처로 받은 쿠폰이
 * 다운로드 → 적용 → 사용 완료 / 기한 만료 / 적용 취소로 이어진 기록과, 30일 제한 때문에 다른 기준 가게에서 같은 가게를 다시 봐도
 * 받지 않은 노출을 만든다. 소개 예약에는 쿠폰을 붙이지 않는다(E17b 수수료 기록과 맞춤).
 */
public final class DemoCouponPlanner {

    /** '쿠폰 재발급 제한' 30 × 24시간(05-policy, FR-CPN-04). */
    static final Duration REDOWNLOAD_WAIT = Duration.ofHours(30L * 24);
    /** 그 가게의 방문 처리된 회원 예약 중 쿠폰을 받아 적용한 비율. */
    static final double APPLY_RATE = 0.35;
    /** 적용된 쿠폰 중 사장님이 사용 확인까지 한 비율(나머지는 '예약에 적용됨' 그대로, FR-CPN-08). */
    static final double CONFIRM_USE_RATE = 0.6;
    /** 행사마다 예약에 쓰지 않은 다운로드 수(기한 만료 · 다운로드됨 사례). */
    static final int UNUSED_DOWNLOADS_PER_EVENT = 12;
    /** 손님이 취소한 그 가게 회원 예약 중 적용됐던 쿠폰이 있던 비율(적용 취소 사례). */
    static final double CANCELED_WITH_COUPON_RATE = 0.5;
    /** 받은 뒤 30일 안에 다른 기준 가게에서 같은 가게를 다시 본 노출(중복 차단 사례) 수. */
    static final int BLOCKED_REVISITS = 10;
    /** 가상 데이터의 끝: 기간 마지막 날 다음 날 00:00(운영 시간대). 이때 기한이 지난 미사용 쿠폰은 만료로 둔다. */
    static final Instant DATA_END = DemoReservationPlanner.PERIOD_END.plusDays(1)
            .atStartOfDay(DemoReservationPlanner.SERVICE_ZONE).toInstant();

    /** 가게 쿠폰 행사(3.2). 쓰지 않는 칸은 null. */
    public record Event(String key, String storeCode, String benefitType, Integer discountAmount, Integer discountPercent,
            Integer maxDiscountAmount, String serviceName, Integer serviceValue, int validDays, int totalQuantity,
            Instant confirmedAt, Instant stoppedAt) {

        boolean issuableAt(Instant at) {
            return !at.isBefore(confirmedAt) && (stoppedAt == null || at.isBefore(stoppedAt));
        }

        /** 3.2 「할인액」: 정액은 방문 금액까지, 정률은 1원 미만 버림 · 최대 할인액까지, 서비스는 0. */
        int discountFor(int visitAmount) {
            return switch (benefitType) {
                case "fixed_discount" -> Math.min(discountAmount, visitAmount);
                case "percent_discount" -> {
                    long raw = (long) visitAmount * discountPercent / 100;
                    yield (int) (maxDiscountAmount == null ? raw : Math.min(raw, maxDiscountAmount));
                }
                default -> 0;
            };
        }
    }

    /** 쿠폰을 받게 한(또는 받지 않은) 지도 추천 노출. {@code key}로 쿠폰이 출처를 가리킨다. */
    public record SourceExposure(String key, int visitorIndex, String displayId, String baseStoreCode,
            String recommendedStoreCode, int rank, Instant shownAt) {
    }

    /** 시연 사례 이름(테스트 · 로그용). */
    public enum Case { USED, APPLIED, APPLY_CANCELED, EXPIRED, DOWNLOADED }

    public record Coupon(String eventKey, int customerIndex, String storeCode, String baseStoreCode, String exposureKey,
            Instant downloadedAt, Instant expiresAt, String status, String reservationNo, Integer discountApplied,
            Instant usedAt, Case kind) {
    }

    public record Plan(List<Event> events, List<SourceExposure> exposures, List<Coupon> coupons,
            List<SourceExposure> blockedRevisits) {
    }

    private final List<SeedRegionData.Store> stores;
    private final Random random;

    private DemoCouponPlanner(List<SeedRegionData.Store> stores, long seed) {
        this.stores = stores;
        this.random = new Random(seed);
    }

    public static Plan plan(List<SeedRegionData.Store> stores, DemoReservationPlanner.Plan reservations,
            DemoExplorationPlanner.Plan exploration, long seed) {
        return new DemoCouponPlanner(stores, seed).build(reservations, exploration);
    }

    /** 행사 틀(시드 코드로 고정, 가게가 없으면 건너뜀). */
    static List<Event> events() {
        return List.of(
                new Event("C1", "S08", "fixed_discount", 3000, null, null, null, null, 7, 80,
                        kst(2026, 9, 1), null),
                new Event("C2", "S05", "percent_discount", null, 10, 3000, null, null, 7, 60,
                        kst(2026, 9, 10), null),
                new Event("C3", "S10", "service", null, null, null, "선물 포장", 2000, 14, 40,
                        kst(2026, 9, 15), kst(2026, 11, 20)),
                new Event("C4", "S12", "fixed_discount", 5000, null, null, null, null, 7, 50,
                        kst(2026, 10, 1), null));
    }

    private Plan build(DemoReservationPlanner.Plan base, DemoExplorationPlanner.Plan exploration) {
        Set<String> storeCodes = stores.stream().map(SeedRegionData.Store::code).collect(Collectors.toSet());
        List<Event> events = events().stream().filter(e -> storeCodes.contains(e.storeCode())).toList();
        Map<Integer, Integer> visitorOf = new HashMap<>();
        Map<Integer, Instant> visitorSince = new HashMap<>();
        exploration.visitors().stream().filter(v -> v.customerIndex() != null).forEach(v -> {
            visitorOf.put(v.customerIndex(), v.index());
            visitorSince.put(v.customerIndex(), v.createdAt());
        });
        Set<String> referrals = base.courseLinks().stream().filter(CourseLink::referral)
                .map(CourseLink::reservationNo).collect(Collectors.toSet());
        List<Customer> members = base.customers().stream()
                .filter(c -> "member".equals(c.type()) && visitorOf.containsKey(c.index())).toList();

        List<SourceExposure> exposures = new ArrayList<>();
        List<Coupon> coupons = new ArrayList<>();
        List<SourceExposure> blocked = new ArrayList<>();
        /** (손님, 행사) → 받은 시각들. 30일 제한 검사. */
        Map<String, List<Instant>> downloads = new HashMap<>();

        List<Reservation> sorted = base.reservations().stream()
                .sorted(Comparator.comparing(Reservation::requestedAt)).toList();
        for (Event event : events) {
            int issued = 0;
            // 적용 → 사용 완료 · 적용됨 · 적용 취소
            for (Reservation r : sorted) {
                if (issued >= event.totalQuantity() || !r.storeCode().equals(event.storeCode())
                        || referrals.contains(r.reservationNo())
                        || !visitorOf.containsKey(r.customerIndex())
                        || !"member".equals(base.customers().get(r.customerIndex()).type())) {
                    continue;
                }
                boolean visited = "visited".equals(r.status());
                boolean customerCanceled = "customer_canceled".equals(r.cancelReason());
                if (!(visited && random.nextDouble() < APPLY_RATE)
                        && !(customerCanceled && random.nextDouble() < CANCELED_WITH_COUPON_RATE)) {
                    continue;
                }
                // 신청 전에 받았고, 신청 시각에 기한 안(FR-CPN-05)
                Instant downloadedAt = r.requestedAt().minus(between(10, (event.validDays() - 1) * 24 * 60), ChronoUnit.MINUTES);
                if (!event.issuableAt(downloadedAt) || !canDownload(downloads, r.customerIndex(), event, downloadedAt)
                        || !seenAfter(visitorSince.get(r.customerIndex()), downloadedAt)) {
                    continue;
                }
                SourceExposure source = exposure(exposures, visitorOf.get(r.customerIndex()), event, downloadedAt);
                Instant expiresAt = downloadedAt.plus(Duration.ofDays(event.validDays()));
                if (visited) {
                    boolean used = random.nextDouble() < CONFIRM_USE_RATE;
                    coupons.add(new Coupon(event.key(), r.customerIndex(), event.storeCode(), source.baseStoreCode(),
                            source.key(), downloadedAt, expiresAt, used ? "used" : "applied", r.reservationNo(),
                            event.discountFor(r.visitAmount()),
                            used ? r.visitedAt().plus(between(1, 30), ChronoUnit.MINUTES) : null,
                            used ? Case.USED : Case.APPLIED));
                } else {
                    coupons.add(new Coupon(event.key(), r.customerIndex(), event.storeCode(), source.baseStoreCode(),
                            source.key(), downloadedAt, expiresAt, "apply_canceled", r.reservationNo(), null, null,
                            Case.APPLY_CANCELED));
                }
                downloads.computeIfAbsent(r.customerIndex() + "/" + event.key(), k -> new ArrayList<>()).add(downloadedAt);
                issued++;
            }
            // 예약에 쓰지 않은 다운로드 → 기한 만료 · 다운로드됨
            for (int i = 0; i < UNUSED_DOWNLOADS_PER_EVENT && issued < event.totalQuantity() && !members.isEmpty(); i++) {
                Customer member = members.get(random.nextInt(members.size()));
                // 첫 번째는 기간 끝 무렵에 받아 데이터 끝에도 기한 안(다운로드됨 사례가 꼭 있게)
                Instant downloadedAt = randomTime(event, i == 0);
                if (downloadedAt == null || !canDownload(downloads, member.index(), event, downloadedAt)
                        || !seenAfter(visitorSince.get(member.index()), downloadedAt)) {
                    continue;
                }
                SourceExposure source = exposure(exposures, visitorOf.get(member.index()), event, downloadedAt);
                Instant expiresAt = downloadedAt.plus(Duration.ofDays(event.validDays()));
                boolean expired = !expiresAt.isAfter(DATA_END);
                coupons.add(new Coupon(event.key(), member.index(), event.storeCode(), source.baseStoreCode(),
                        source.key(), downloadedAt, expiresAt, expired ? "expired" : "downloaded", null, null, null,
                        expired ? Case.EXPIRED : Case.DOWNLOADED));
                downloads.computeIfAbsent(member.index() + "/" + event.key(), k -> new ArrayList<>()).add(downloadedAt);
                issued++;
            }
        }
        // 받은 뒤 30일 안에 다른 기준 가게에서 같은 가게를 다시 봄 → 30일 제한(행사 단위)이라 받지 않음(FR-CPN-04)
        List<Coupon> candidates = new ArrayList<>(coupons);
        for (int i = 0; i < BLOCKED_REVISITS && !candidates.isEmpty(); i++) {
            Coupon c = candidates.remove(random.nextInt(candidates.size()));
            Instant shownAt = c.downloadedAt().plus(between(60, 25 * 24 * 60), ChronoUnit.MINUTES);
            if (shownAt.isAfter(DATA_END)) {
                continue;
            }
            String otherBase = otherStore(c.storeCode(), c.baseStoreCode());
            blocked.add(new SourceExposure("B" + blocked.size(), visitorOf.get(c.customerIndex()), uuid(), otherBase,
                    c.storeCode(), 1 + random.nextInt(3), shownAt));
        }
        return new Plan(events, exposures, coupons, blocked);
    }

    /** 출처 노출(다운로드 30분 전까지)이 그 방문자가 생긴 뒤인지. */
    private static boolean seenAfter(Instant visitorCreatedAt, Instant downloadedAt) {
        return downloadedAt.minus(Duration.ofMinutes(31)).isAfter(visitorCreatedAt);
    }

    /** 같은 행사에서 받은 지 30 × 24시간 안이면 받을 수 없다(만료 · 적용 취소도 센다). */
    private boolean canDownload(Map<String, List<Instant>> downloads, int customer, Event event, Instant at) {
        return downloads.getOrDefault(customer + "/" + event.key(), List.of()).stream()
                .noneMatch(d -> Duration.between(d, at).abs().compareTo(REDOWNLOAD_WAIT) < 0);
    }

    /** 다운로드 몇 분 전에 그 가게를 추천 카드로 본 노출(쿠폰 출처, FR-CPN-10). 기준 가게는 행사 가게가 아닌 곳. */
    private SourceExposure exposure(List<SourceExposure> exposures, int visitor, Event event, Instant downloadedAt) {
        SourceExposure e = new SourceExposure("E" + exposures.size(), visitor, uuid(), otherStore(event.storeCode(), null),
                event.storeCode(), 1 + random.nextInt(3), downloadedAt.minus(between(1, 30), ChronoUnit.MINUTES));
        exposures.add(e);
        return e;
    }

    private String otherStore(String not, String alsoNot) {
        List<String> others = stores.stream().map(SeedRegionData.Store::code)
                .filter(code -> !code.equals(not) && !code.equals(alsoNot)).toList();
        return others.get(random.nextInt(others.size()));
    }

    /** 행사 진행 중 · 데이터 기간 안의 임의 시각(10 ~ 22시). late면 기간 마지막 (쿠폰 기간 − 1)일 안. 없으면 null. */
    private Instant randomTime(Event event, boolean late) {
        LocalDate from = late ? DemoReservationPlanner.PERIOD_END.minusDays(event.validDays() - 2)
                : DemoReservationPlanner.PERIOD_START;
        long days = ChronoUnit.DAYS.between(from, DemoReservationPlanner.PERIOD_END) + 1;
        for (int attempt = 0; attempt < 20; attempt++) {
            LocalDate day = from.plusDays(random.nextInt((int) days));
            Instant at = LocalDateTime.of(day, LocalTime.of(10, 0).plusMinutes(between(0, 12 * 60)))
                    .atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
            if (event.issuableAt(at)) {
                return at;
            }
        }
        return null;
    }

    private static Instant kst(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(9, 0).atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
    }

    private String uuid() {
        return new java.util.UUID(random.nextLong(), random.nextLong()).toString();
    }

    private int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
