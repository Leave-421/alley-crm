package com.alleycrm.recommend.recommendation;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.recommend.candidate.CandidateRequester;
import com.alleycrm.recommend.candidate.CandidateService;
import com.alleycrm.recommend.domain.ReasonType;
import com.alleycrm.recommend.domain.RecommendationBadge;
import com.alleycrm.recommend.record.ExplorationRecordService;
import com.alleycrm.recommend.record.ExposureItem;
import com.alleycrm.recommend.record.RecordedExposure;
import com.alleycrm.recommend.recommendation.RecommendationDtos.Card;
import com.alleycrm.recommend.recommendation.RecommendationDtos.CardStore;
import com.alleycrm.recommend.recommendation.RecommendationDtos.ExposureItemResponse;
import com.alleycrm.recommend.recommendation.RecommendationDtos.ExposuresResponse;
import com.alleycrm.recommend.recommendation.RecommendationDtos.PublicData;
import com.alleycrm.recommend.recommendation.RecommendationDtos.RecommendationsResponse;
import com.alleycrm.recommend.recommendation.RecommendationRepository.StoreInfo;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.RecommendScoreService;
import com.alleycrm.recommend.score.RecommendScoreService.RecommendScore;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지도 추천(E7 #21 · Task #206, CONTRACTS_API 3.1, 05-policy 「다음 방문 추천」 1 ~ 6단계).
 * 후보(E4) → 최종 점수(E5 · E6) → 순위 · 배지 · 이유 문구 · 묶음. 조회는 기록하지 않고, 표시 기록은 그린 카드만 남긴다(E3).
 */
@Service
public class RecommendationService {

    static final String BASE_TIME_NOW = "now";
    static final String BASE_TIME_RESERVATION = "reservation";

    /**
     * 요청자(세션으로 정함, 3.1 「손님 식별에 따른 차이」). 회원이면 손님 ID와 회원정보의 성별 · 연령대, 익명 · 사장님 · 상인회는 식별 안 됨.
     */
    public record Requester(Long memberCustomerId) {

        public static final Requester UNIDENTIFIED = new Requester(null);
    }

    /** 순위까지 매긴 후보 한 곳(전체 순위). */
    record Ranked(int rank, StoreInfo store, RecommendationBadge badge, ReasonType reasonType, String reasonText,
            boolean couponBenefit) {
    }

    record Ranking(Instant baseTime, String baseTimeSource, StoreInfo base, List<Ranked> all) {
    }

    private final RecommendationRepository repository;
    private final CandidateService candidates;
    private final RecommendScoreService scores;
    private final ExplorationRecordService records;
    private final SettingService settings;
    private final Clock clock;

    public RecommendationService(RecommendationRepository repository, CandidateService candidates,
            RecommendScoreService scores, ExplorationRecordService records, SettingService settings, Clock clock) {
        this.repository = repository;
        this.candidates = candidates;
        this.scores = scores;
        this.records = records;
        this.settings = settings;
        this.clock = clock;
    }

    /** 지도 추천 조회. 기준 가게가 손님에게 보이지 않으면 404. 아무것도 기록하지 않는다. */
    @Transactional(readOnly = true)
    public RecommendationsResponse recommend(long baseStoreId, int page, Requester requester) {
        StoreInfo base = repository.findVisibleStore(baseStoreId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Ranking ranking = rank(base, requester);
        int size = settings.getInt(regionOf(baseStoreId), SettingKey.RECOMMEND_COUNT);
        int pageCount = (ranking.all().size() + size - 1) / size;
        int actualPage = pageCount == 0 ? 0 : page % pageCount;
        List<Ranked> slice = ranking.all().stream().skip((long) actualPage * size).limit(size).toList();
        PublicData publicData = slice.stream().anyMatch(r -> r.badge() == RecommendationBadge.LOCAL_POPULAR)
                ? repository.publicData().map(p -> new PublicData(p.source(), p.period())).orElse(null)
                : null;
        return new RecommendationsResponse(String.valueOf(baseStoreId), ranking.baseTime(), ranking.baseTimeSource(),
                actualPage, pageCount, slice.stream().map(RecommendationService::card).toList(), publicData);
    }

    /**
     * 지도 추천 표시 기록. 그린 가게가 지금 이 기준 가게의 추천 후보가 아니면 409이고 아무것도 기록하지 않는다.
     * 순위 · 배지 · 근거 종류는 기록 시점에 다시 계산한 값으로 남긴다.
     */
    @Transactional
    public ExposuresResponse recordExposures(UUID displayId, long baseStoreId, int page, List<Long> storeIds,
            Requester requester) {
        StoreInfo base = repository.findVisibleStore(baseStoreId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Map<Long, Ranked> ranked = rank(base, requester).all().stream()
                .collect(Collectors.toMap(r -> r.store().id(), Function.identity()));
        List<ExposureItem> items = new ArrayList<>();
        for (long storeId : storeIds) {
            Ranked r = ranked.get(storeId);
            if (r == null) {
                throw new ApiException(ErrorCode.CONFLICT, "지금 이 가게의 추천 후보가 아닌 가게가 있습니다.");
            }
            items.add(new ExposureItem(storeId, r.rank(), r.badge(), r.reasonType()));
        }
        Map<Long, Long> exposureIds = records.recordExposures(displayId, baseStoreId, page, items).stream()
                .collect(Collectors.toMap(RecordedExposure::storeId, RecordedExposure::exposureId));
        return new ExposuresResponse(storeIds.stream()
                .map(id -> new ExposureItemResponse(String.valueOf(id), String.valueOf(exposureIds.get(id))))
                .toList());
    }

    /** 전체 순위: 최종 점수 내림차순, 동점은 가게 ID 오름차순(3.1 「구현 기준」). 쿠폰 행사는 순위에 쓰지 않는다(FR-CPN-02). */
    Ranking rank(StoreInfo base, Requester requester) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant baseTime = now;
        String source = BASE_TIME_NOW;
        CandidateRequester candidateRequester = CandidateRequester.UNIDENTIFIED;
        Profile profile = Profile.UNKNOWN;
        if (requester.memberCustomerId() != null) {
            long customerId = requester.memberCustomerId();
            candidateRequester = CandidateRequester.member(customerId);
            profile = repository.memberProfile(customerId);
            Optional<LocalDateTime> upcoming = repository.nearestUpcomingReservation(customerId, base.id(),
                    LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            if (upcoming.isPresent()) {
                baseTime = upcoming.get().toInstant(ZoneOffset.UTC);
                source = BASE_TIME_RESERVATION;
            }
        }
        List<Long> candidateIds = candidates.findCandidates(base.id(), baseTime, candidateRequester);
        List<RecommendScore> scored = scores.score(base.id(), candidateIds, baseTime, profile).stream()
                .sorted(Comparator.comparingDouble(RecommendScore::finalScore).reversed()
                        .thenComparingLong(RecommendScore::storeId))
                .toList();
        List<Long> ids = scored.stream().map(RecommendScore::storeId).toList();
        Map<Long, StoreInfo> stores = repository.findStores(ids);
        Set<Long> withCoupon = repository.storesWithCouponBenefit(ids);
        List<Ranked> all = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            RecommendScore s = scored.get(i);
            RecommendationBadge badge = s.visitDataBased() ? RecommendationBadge.VISIT_DATA : RecommendationBadge.LOCAL_POPULAR;
            ReasonType reason = RecommendationReason.typeOf(s.visitDataBased(), s.defaultTerms());
            all.add(new Ranked(i + 1, stores.get(s.storeId()), badge, reason,
                    RecommendationReason.textOf(reason, base.category()), withCoupon.contains(s.storeId())));
        }
        return new Ranking(baseTime, source, base, all);
    }

    private long regionOf(long storeId) {
        return repository.regionOf(storeId);
    }

    private static Card card(Ranked r) {
        StoreInfo s = r.store();
        return new Card(r.rank(), new CardStore(String.valueOf(s.id()), s.name(), s.category(), s.alley(), s.mapX(), s.mapY()),
                r.badge(), r.reasonType(), r.reasonText(), r.couponBenefit());
    }
}
