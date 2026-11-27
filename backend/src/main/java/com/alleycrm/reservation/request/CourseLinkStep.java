package com.alleycrm.reservation.request;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.reservation.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 40 코스 연결 (D12, FR-RSV-11 · 기획 11-26 (2)). '다음 코스로 예약하기'일 때만 돈다(일반 예약은 건너뜀, #5).
 * <ul>
 *   <li>기준 예약: 같은 손님의 예약이어야 한다(아니면 not_found). 취소 · 거절된 기준 예약은 쓸 수 없다(#1, conflict)</li>
 *   <li>시간: 기준 예약일(운영 시간대)과 같은 날이면 기준 예약 시각보다 늦어야 한다(#6, conflict). 다른 날은 제한 없음(#7).
 *       겹침 판정은 두지 않는다(11-26 (2))</li>
 *   <li>정식 코스 제안(courseProposalId): 그 기준 예약 · 이 가게의 제안이어야 한다(conflict). 기준 예약일 당일이 끝나기 전
 *       (신청 시각 기준)이면 소개 후보로 제안 id · 보낸 가게를 남기고, 지났으면 코스로만 연결한다(05-policy 「소개 판정」)</li>
 * </ul>
 */
@Component
class CourseLinkStep implements ReservationRequestStep {

    private final JdbcTemplate jdbc;

    CourseLinkStep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return COURSE;
    }

    @Override
    public void apply(ReservationRequestContext ctx) {
        Long rootId = ctx.getCourseRootId();
        if (rootId == null) {
            if (ctx.getCourseProposalId() != null) {
                throw new ApiException(ErrorCode.INVALID); // 제안만 있고 기준 예약이 없음
            }
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT store_id, customer_id, start_at, status FROM reservation WHERE id = ?", rootId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("customer_id")).longValue() != ctx.getCustomerId()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Map<String, Object> base = rows.get(0);
        long baseStoreId = ((Number) base.get("store_id")).longValue();
        Instant baseStart = ((LocalDateTime) base.get("start_at")).toInstant(ZoneOffset.UTC);
        if (ReservationStatus.CANCELED.equals(base.get("status"))) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        if (baseStoreId == ctx.getStoreId()) {
            throw new ApiException(ErrorCode.CONFLICT); // 다음 코스는 다른 가게
        }
        LocalDate baseDay = LocalDate.ofInstant(baseStart, SlotRules.SERVICE_ZONE);
        LocalDate day = LocalDate.ofInstant(ctx.getStartAt(), SlotRules.SERVICE_ZONE);
        if (day.equals(baseDay) && !ctx.getStartAt().isAfter(baseStart)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        Long proposalId = ctx.getCourseProposalId();
        if (proposalId == null) {
            return;
        }
        List<Map<String, Object>> p = jdbc.queryForList(
                "SELECT base_reservation_id, recommended_store_id FROM course_proposal WHERE id = ?", proposalId);
        if (p.isEmpty()
                || ((Number) p.get(0).get("base_reservation_id")).longValue() != rootId
                || ((Number) p.get(0).get("recommended_store_id")).longValue() != ctx.getStoreId()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Instant deadline = baseDay.plusDays(1).atStartOfDay(SlotRules.SERVICE_ZONE).toInstant();
        if (ctx.getRequestedAt().isBefore(deadline)) {
            ctx.setSenderStoreId(baseStoreId); // 소개 후보
        } else {
            ctx.setCourseProposalId(null); // 마감 뒤: 코스로만 연결, 소개 아님
        }
    }
}
