package com.alleycrm.reservation.owner;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.coupon.dto.VisitCouponDiscount;
import com.alleycrm.coupon.service.CouponApplyService;
import com.alleycrm.coupon.service.CouponVisitService;
import com.alleycrm.reservation.Reservation;
import com.alleycrm.reservation.ReservationResponse;
import com.alleycrm.reservation.ReservationResponse.AppliedCoupon;
import com.alleycrm.reservation.ReservationStateService;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 사장님 예약: 승인 · 거절(D3), 날짜별 목록 · 상세(D4), 방문 처리(D11). 계약: CONTRACTS_API 「사장님 예약」.
 * 적용 쿠폰은 E14의 {@link CouponApplyService#appliedCouponFor}로 채운다(3.6 S-1).
 */
@RestController
class OwnerReservationController {

    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String ITEM_COLUMNS = """
            SELECT r.id, r.reservation_no, r.start_at, r.party_size, r.status, r.cancel_reason, r.requested_at,
                   r.request_note, r.deposit_amount, r.course_proposal_id, r.visit_amount, r.visited_at,
                   c.type AS customer_type, c.name
            FROM reservation r JOIN customer c ON c.id = r.customer_id
            """;

    private final ReservationStateService states;
    private final OwnerStoreResolver owners;
    private final JdbcTemplate jdbc;
    private final CouponApplyService couponApply;
    private final CouponVisitService couponVisit;
    private final SettingService settings;
    private final ObjectMapper json;

    OwnerReservationController(ReservationStateService states, OwnerStoreResolver owners, JdbcTemplate jdbc,
            CouponApplyService couponApply, CouponVisitService couponVisit, SettingService settings, ObjectMapper json) {
        this.states = states;
        this.owners = owners;
        this.jdbc = jdbc;
        this.couponApply = couponApply;
        this.couponVisit = couponVisit;
        this.settings = settings;
        this.json = json;
    }

    @PostMapping("/api/v1/owner/reservations/{id}/confirm")
    ReservationResponse confirm(@PathVariable long id, Authentication auth) {
        Reservation r = states.confirm(owners.storeIdOf(auth), id);
        return ReservationResponse.of(r, couponApply.appliedCouponFor(r.getId()));
    }

    @PostMapping("/api/v1/owner/reservations/{id}/reject")
    ReservationResponse reject(@PathVariable long id, Authentication auth) {
        // 거절하면 쿠폰은 '적용 취소'가 되어 null이다
        Reservation r = states.reject(owners.storeIdOf(auth), id);
        return ReservationResponse.of(r, couponApply.appliedCouponFor(r.getId()));
    }

    /**
     * 방문 처리(D11): 결제 금액(쿠폰 할인 전)을 받는다. 응답은 Reservation 모양에 실제 쿠폰 할인액
     * {@code couponDiscountAmount}(E14 discountFor, 쿠폰 없으면 null)를 더한다.
     */
    @PostMapping("/api/v1/owner/reservations/{id}/visit")
    Map<String, Object> visit(@PathVariable long id, @RequestBody VisitRequest body, Authentication auth) {
        Reservation r = states.visit(owners.storeIdOf(auth), id, body == null ? null : body.visitAmount());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = new LinkedHashMap<>(json.convertValue(
                ReservationResponse.of(r, couponApply.appliedCouponFor(r.getId())), Map.class));
        out.put("couponDiscountAmount", couponVisit.discountFor(r.getId(), r.getVisitAmount())
                .map(VisitCouponDiscount::discountAmount).orElse(null));
        return out;
    }

    record VisitRequest(Integer visitAmount) {
    }

    /** 운영 시간대 기준 그 날짜에 시작하는 내 가게 예약, 시각 순. 하루치라 페이지를 나누지 않는다 */
    @GetMapping("/api/v1/owner/reservations")
    Map<String, Object> byDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        long storeId = owners.storeIdOf(auth);
        LocalDateTime from = utc(date.atStartOfDay(SERVICE_ZONE).toInstant());
        LocalDateTime to = utc(date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant());
        List<OwnerReservationItem> items = jdbc.query(ITEM_COLUMNS + """
                WHERE r.store_id = ? AND r.start_at >= ? AND r.start_at < ?
                ORDER BY r.start_at, r.id
                """, (rs, i) -> item(rs), storeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("referralFeeRate", referralFeeRate(storeId));
        out.put("items", items);
        return out;
    }

    /** 예약 상세(O-13 · O-01 방문 처리 화면). 다른 가게 예약은 404 */
    @GetMapping("/api/v1/owner/reservations/{id}")
    Map<String, Object> detail(@PathVariable long id, Authentication auth) {
        long storeId = owners.storeIdOf(auth);
        List<OwnerReservationItem> rows = jdbc.query(ITEM_COLUMNS + " WHERE r.id = ? AND r.store_id = ?",
                (rs, i) -> item(rs), id, storeId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = new LinkedHashMap<>(json.convertValue(rows.get(0), Map.class));
        out.put("referralFeeRate", referralFeeRate(storeId));
        return out;
    }

    /** 소개 수수료율(설정값, NFR-04). 화면의 수수료 미리보기용 */
    private BigDecimal referralFeeRate(long storeId) {
        Long regionId = jdbc.queryForObject("SELECT region_id FROM store WHERE id = ?", Long.class, storeId);
        return settings.getDecimal(regionId, SettingKey.REFERRAL_FEE_RATE);
    }

    private OwnerReservationItem item(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        return new OwnerReservationItem(
                String.valueOf(id),
                rs.getString("reservation_no"),
                instant(rs.getObject("start_at", LocalDateTime.class)),
                rs.getInt("party_size"),
                rs.getString("status"),
                rs.getString("cancel_reason"),
                instant(rs.getObject("requested_at", LocalDateTime.class)),
                rs.getString("request_note"),
                rs.getInt("deposit_amount"),
                rs.getString("customer_type"),
                NameMask.mask(rs.getString("name")),
                rs.getObject("course_proposal_id") != null,
                (Integer) rs.getObject("visit_amount"),
                instant(rs.getObject("visited_at", LocalDateTime.class)),
                couponApply.appliedCouponFor(id));
    }

    /**
     * 사장님 목록 · 상세 한 줄. 손님 이름은 가린 값만, 전화번호 · 보낸 가게는 넣지 않는다(FR-CUS-05, FR-RSV-04).
     */
    record OwnerReservationItem(
            String id,
            String reservationNo,
            Instant startAt,
            int partySize,
            String status,
            String cancelReason,
            Instant requestedAt,
            String requestNote,
            int depositAmount,
            String customerType,
            String maskedName,
            boolean fromRecommendation,
            Integer visitAmount,
            Instant visitedAt,
            AppliedCoupon coupon) {
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
