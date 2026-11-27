package com.alleycrm.seed;

import com.alleycrm.seed.DemoReservationPlanner.CourseLink;
import com.alleycrm.seed.DemoReservationPlanner.Proposal;
import com.alleycrm.seed.DemoReservationPlanner.Reservation;
import com.alleycrm.setting.SettingKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * ⑥ 가상 데이터 2(E17 #268, 06-data 「가상 데이터 생성」 9 · 10단계): 가상 데이터 1의 계획 · ID 위에
 * 방문자 · 지도 추천 노출, 정식 코스 제안 · 코스 연결, 소개 수수료 · 가게 → 기금 거래, 지난 달 정산 확정을 넣는다.
 * 모든 행은 {@code data_source = 'demo'}이고 시각 칸은 UTC 값이다. 지표는 넣지 않는다(화면이 이 원천에서 계산).
 *
 * <p>수수료는 D15와 같은 규칙: 방문 처리된 소개 예약마다 한 번, 계산 금액 = 방문 금액(쿠폰 없음) × 수수료율(설정 기본값),
 * 1원 미만 버림, 받은 가게 → 권역 기금. 정산 달은 방문 처리된 달(운영 시간대).
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(DemoDataProperties.class)
class DemoCourseSeeder {

    static final String DEMO = DemoReservationSeeder.DEMO;
    /** 시연 시점 기준 정산을 확정해 둘 달(마지막 달 11월은 집계 중으로 둔다). */
    static final YearMonth LAST_CONFIRMED_MONTH = YearMonth.of(2026, 10);

    private final JdbcTemplate jdbc;
    private final DemoDataProperties properties;

    DemoCourseSeeder(JdbcTemplate jdbc, DemoDataProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    record Result(int visitors, int exposures, int proposals, int courseReservations, int referralFees,
            long totalFee, int confirmedSettlements) {
    }

    Result insert(SeedRegionData data, RegionStoreSeeder.Result regionStores, DemoReservationSeeder.Result reservations) {
        Map<String, Long> storeIds = regionStores.storeIdsByCode();
        DemoReservationPlanner.Plan plan = reservations.plan();

        DemoExplorationPlanner.Plan exploration = DemoExplorationPlanner.plan(data.stores(), plan, properties.randomSeed() + 1);
        Map<Integer, Long> visitorIds = insertVisitors(exploration.visitors(), reservations.customerIds());
        insertExposures(exploration.exposures(), visitorIds, storeIds);

        Map<String, Long> proposalIds = insertProposals(plan.proposals(), reservations.reservationIds(), storeIds);
        linkCourses(plan.courseLinks(), reservations.reservationIds(), proposalIds, storeIds);
        Fees fees = insertReferralFees(plan, reservations.reservationIds(), storeIds, regionStores.regionId());
        int confirmed = confirmSettlements(regionStores.regionId(), fees.months());
        return new Result(exploration.visitors().size(), exploration.exposures().size(), plan.proposals().size(),
                plan.courseLinks().size(), fees.count(), fees.total(), confirmed);
    }

    // ---- 탐색 기록 ----

    private Map<Integer, Long> insertVisitors(List<DemoExplorationPlanner.Visitor> visitors, Map<Integer, Long> customerIds) {
        Map<Integer, Long> ids = new HashMap<>();
        for (DemoExplorationPlanner.Visitor v : visitors) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO visitor (anon_id, customer_id, linked_at, created_at) VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, v.anonId());
                ps.setObject(2, v.customerIndex() == null ? null : customerIds.get(v.customerIndex()));
                ps.setObject(3, utc(v.linkedAt()));
                ps.setObject(4, utc(v.createdAt()));
                return ps;
            }, key);
            ids.put(v.index(), key.getKey().longValue());
        }
        return ids;
    }

    private void insertExposures(List<DemoExplorationPlanner.Exposure> exposures, Map<Integer, Long> visitorIds,
            Map<String, Long> storeIds) {
        List<Object[]> rows = exposures.stream().map(e -> new Object[] {
                visitorIds.get(e.visitorIndex()), e.displayId(), storeId(storeIds, e.baseStoreCode()),
                storeId(storeIds, e.recommendedStoreCode()), e.rank(), e.page(), e.badge(), e.reasonType(),
                utc(e.shownAt()), DEMO}).toList();
        jdbc.batchUpdate("""
                INSERT INTO recommendation_exposure (visitor_id, display_id, base_store_id, recommended_store_id, `rank`, page,
                                                     badge, reason_type, shown_at, data_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    // ---- 정식 코스 제안 · 코스 연결 ----

    /** (기준 예약번호, 가게 코드) → 제안 ID. */
    private Map<String, Long> insertProposals(List<Proposal> proposals, Map<String, Long> reservationIds,
            Map<String, Long> storeIds) {
        Map<String, Long> ids = new HashMap<>();
        for (Proposal p : proposals) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO course_proposal (base_reservation_id, recommended_store_id, first_shown_at, data_source)
                        VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, reservationId(reservationIds, p.baseReservationNo()));
                ps.setLong(2, storeId(storeIds, p.storeCode()));
                ps.setObject(3, utc(p.firstShownAt()));
                ps.setString(4, DEMO);
                return ps;
            }, key);
            ids.put(p.baseReservationNo() + "/" + p.storeCode(), key.getKey().longValue());
        }
        return ids;
    }

    /** 코스 예약에 기준 예약을 잇고, 소개 후보면 제안 · 보낸 가게를 단다(D12가 신청 때 남기는 칸). */
    private void linkCourses(List<CourseLink> links, Map<String, Long> reservationIds, Map<String, Long> proposalIds,
            Map<String, Long> storeIds) {
        List<Object[]> rows = links.stream().map(l -> new Object[] {
                reservationId(reservationIds, l.rootReservationNo()),
                l.referral() ? proposalIds.get(l.rootReservationNo() + "/" + l.proposalStoreCode()) : null,
                l.referral() ? storeId(storeIds, l.senderStoreCode()) : null,
                reservationId(reservationIds, l.reservationNo())}).toList();
        jdbc.batchUpdate("""
                UPDATE reservation SET course_root_id = ?, course_proposal_id = ?, sender_store_id = ? WHERE id = ?
                """, rows);
    }

    // ---- 소개 수수료 · 거래 · 정산 ----

    record Fees(int count, long total, Map<String, List<Long>> months) {
    }

    private Fees insertReferralFees(DemoReservationPlanner.Plan plan, Map<String, Long> reservationIds,
            Map<String, Long> storeIds, long regionId) {
        BigDecimal rate = new BigDecimal(SettingKey.REFERRAL_FEE_RATE.defaultValue());
        Map<String, Reservation> byNo = plan.reservations().stream()
                .collect(Collectors.toMap(Reservation::reservationNo, Function.identity()));
        long fundWallet = wallet("fund", regionId);
        Map<String, List<Long>> months = new TreeMap<>();
        int count = 0;
        long total = 0;
        for (CourseLink link : plan.courseLinks()) {
            Reservation r = byNo.get(link.reservationNo());
            if (!link.referral() || !"visited".equals(r.status())) {
                continue;
            }
            int fee = rate.multiply(BigDecimal.valueOf(r.visitAmount())).setScale(0, RoundingMode.DOWN).intValueExact();
            String month = YearMonth.from(r.visitedAt().atZone(DemoReservationPlanner.SERVICE_ZONE)).toString();
            long reservationId = reservationId(reservationIds, r.reservationNo());
            long receiving = storeId(storeIds, r.storeCode());
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO referral_fee (reservation_id, receiving_store_id, sender_store_id, visit_amount,
                                                  coupon_discount, fee_base, fee_rate, fee_amount, coupon_cost,
                                                  settlement_month, data_source, created_at)
                        VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, reservationId);
                ps.setLong(2, receiving);
                ps.setLong(3, storeId(storeIds, link.senderStoreCode()));
                ps.setInt(4, r.visitAmount());
                ps.setInt(5, r.visitAmount());
                ps.setBigDecimal(6, rate);
                ps.setInt(7, fee);
                ps.setString(8, month);
                ps.setString(9, DEMO);
                ps.setObject(10, utc(r.visitedAt()));
                return ps;
            }, key);
            months.computeIfAbsent(month, k -> new ArrayList<>()).add(key.getKey().longValue());
            if (fee > 0) {
                // 받은 가게 → 기금(D15와 같은 거래 종류). 가게 잔액은 마이너스가 될 수 있다(11-25 (4))
                tx(wallet("store", receiving), "referral_fee_charged", -fee, reservationId, r.visitedAt());
                tx(fundWallet, "fund_deposited", fee, reservationId, r.visitedAt());
            }
            count++;
            total += fee;
        }
        jdbc.update("""
                UPDATE wallet w SET balance = (SELECT COALESCE(SUM(t.amount), 0) FROM wallet_tx t WHERE t.wallet_id = w.id)
                WHERE w.owner_type IN ('store', 'fund')
                """);
        return new Fees(count, total, months);
    }

    /** {@link #LAST_CONFIRMED_MONTH}까지의 달은 정산 확정(다음 달 1일 10:00 KST), 수수료에 정산 ID를 단다. */
    private int confirmSettlements(long regionId, Map<String, List<Long>> months) {
        int confirmed = 0;
        for (Map.Entry<String, List<Long>> month : months.entrySet()) {
            YearMonth ym = YearMonth.parse(month.getKey());
            if (ym.isAfter(LAST_CONFIRMED_MONTH)) {
                continue;
            }
            Instant confirmedAt = ym.plusMonths(1).atDay(1).atTime(10, 0)
                    .atZone(DemoReservationPlanner.SERVICE_ZONE).toInstant();
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO settlement (region_id, month, status, confirmed_at) VALUES (?, ?, 'confirmed', ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, regionId);
                ps.setString(2, month.getKey());
                ps.setObject(3, utc(confirmedAt));
                return ps;
            }, key);
            long settlementId = key.getKey().longValue();
            jdbc.batchUpdate("UPDATE referral_fee SET settlement_id = ? WHERE id = ?",
                    month.getValue().stream().map(id -> new Object[] {settlementId, id}).toList());
            confirmed++;
        }
        return confirmed;
    }

    /** 지갑 ID(없으면 잔액 0으로 만든다). */
    private long wallet(String ownerType, long ownerId) {
        List<Long> found = jdbc.queryForList("SELECT id FROM wallet WHERE owner_type = ? AND owner_id = ?", Long.class,
                ownerType, ownerId);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        jdbc.update("INSERT INTO wallet (owner_type, owner_id, balance) VALUES (?, ?, 0)", ownerType, ownerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void tx(long walletId, String type, long amount, long reservationId, Instant at) {
        jdbc.update("""
                INSERT INTO wallet_tx (wallet_id, type, amount, reservation_id, data_source, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, walletId, type, amount, reservationId, DEMO, utc(at));
    }

    private static long storeId(Map<String, Long> storeIds, String code) {
        Long id = storeIds.get(code);
        if (id == null) {
            throw new IllegalStateException("가상 데이터의 가게가 시드에 없습니다: " + code);
        }
        return id;
    }

    private static long reservationId(Map<String, Long> reservationIds, String no) {
        Long id = reservationIds.get(no);
        if (id == null) {
            throw new IllegalStateException("가상 데이터의 예약이 없습니다: " + no);
        }
        return id;
    }

    /** DB의 DATETIME 칸은 UTC 시각 그대로다(JdbcTemplate은 LocalDateTime을 바꾸지 않는다). */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
