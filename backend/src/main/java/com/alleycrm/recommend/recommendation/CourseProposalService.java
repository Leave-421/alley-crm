package com.alleycrm.recommend.recommendation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.record.ExplorationRecordService;
import com.alleycrm.recommend.record.RecordedProposal;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalItem;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalsRecorded;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CourseProposalsResponse;
import com.alleycrm.recommend.recommendation.RecommendationRepository.BaseReservation;
import com.alleycrm.recommend.recommendation.RecommendationRepository.StoreInfo;
import com.alleycrm.recommend.recommendation.RecommendationService.Ranking;
import com.alleycrm.recommend.recommendation.RecommendationService.Requester;
import com.alleycrm.reservation.request.SlotRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정식 코스 제안(E7 #21 · Task #208, CONTRACTS_API 3.1). 기준 가게 · 기준 시각은 요청이 아니라 그 기준 예약에서 정한다.
 * 조회는 기록하지 않고, 표시 기록은 (기준 예약, 추천 가게) 조합당 1건이다(수락률 분모, FR-ANL-06).
 */
@Service
public class CourseProposalService {

    private final RecommendationService recommendations;
    private final RecommendationRepository repository;
    private final ExplorationRecordService records;

    public CourseProposalService(RecommendationService recommendations, RecommendationRepository repository,
            ExplorationRecordService records) {
        this.recommendations = recommendations;
        this.repository = repository;
        this.records = records;
    }

    @Transactional(readOnly = true)
    public CourseProposalsResponse proposals(long baseReservationId, int page, long customerId) {
        Context ctx = context(baseReservationId, customerId);
        RecommendationService.Page p = recommendations.page(ctx.ranking(), page);
        return new CourseProposalsResponse(String.valueOf(baseReservationId), String.valueOf(ctx.base().id()),
                ctx.ranking().baseTime(), applyDeadline(ctx.ranking().baseTime()), p.page(), p.pageCount(), p.cards(),
                p.publicData());
    }

    /** 그린 제안 기록. 지금 이 기준 예약의 추천 후보가 아닌 가게가 하나라도 있으면 409이고 아무것도 기록하지 않는다. */
    @Transactional
    public CourseProposalsRecorded record(long baseReservationId, List<Long> storeIds, long customerId) {
        Context ctx = context(baseReservationId, customerId);
        Set<Long> candidates = ctx.ranking().all().stream().map(r -> r.store().id()).collect(Collectors.toSet());
        if (!candidates.containsAll(storeIds)) {
            throw new ApiException(ErrorCode.CONFLICT, "지금 이 예약의 추천 후보가 아닌 가게가 있습니다.");
        }
        Map<Long, RecordedProposal> recorded = records.recordProposals(baseReservationId, storeIds).stream()
                .collect(Collectors.toMap(RecordedProposal::storeId, r -> r));
        return new CourseProposalsRecorded(storeIds.stream().map(id -> {
            RecordedProposal r = recorded.get(id);
            return new CourseProposalItem(String.valueOf(id), String.valueOf(r.courseProposalId()),
                    r.firstShownAt().toInstant(ZoneOffset.UTC));
        }).toList());
    }

    private record Context(StoreInfo base, Ranking ranking) {
    }

    /** 요청자의 예약이 아니거나 없으면 404(존재를 숨김, IDOR). 기준 가게는 그 예약의 가게, 기준 시각은 예약 시각. */
    private Context context(long baseReservationId, long customerId) {
        BaseReservation reservation = repository.findReservation(baseReservationId)
                .filter(r -> r.customerId() == customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        StoreInfo base = repository.findVisibleStore(reservation.storeId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant baseTime = reservation.startAt().toInstant(ZoneOffset.UTC);
        Ranking ranking = recommendations.rankAt(base, new Requester(customerId), baseTime,
                RecommendationService.BASE_TIME_RESERVATION);
        return new Context(base, ranking);
    }

    /** 소개 예약 신청 마감: 기준 예약일(Asia/Seoul) 다음 날 00:00. 판정은 D12 · E15가 신청 시각으로 한다. */
    static Instant applyDeadline(Instant baseTime) {
        LocalDate day = LocalDate.ofInstant(baseTime, SlotRules.SERVICE_ZONE);
        return day.plusDays(1).atStartOfDay(SlotRules.SERVICE_ZONE).toInstant();
    }
}
