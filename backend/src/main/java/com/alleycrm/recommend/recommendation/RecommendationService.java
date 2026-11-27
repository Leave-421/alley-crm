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
import com.alleycrm.recommend.NextStoreRecommendedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final ApplicationEventPublisher publisher;

    public RecommendationService(RecommendationRepository repository, CandidateService candidates,
            RecommendScoreService scores, ExplorationRecordService records, SettingService settings, Clock clock,
            ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.candidates = candidates;
        this.scores = scores;
        this.records = records;
        this.settings = settings;
        this.clock = clock;
        this.publisher = publisher;
    }

    /** 지도 추천 조회. 기준 가게가 손님에게 보이지 않으면 404. 아무것도 기록하지 않는다. */
    @Transactional(readOnly = true)
    public RecommendationsResponse recommend(long baseStoreId, int page, Requester requester) {
        StoreInfo base = repository.findVisibleStore(baseStoreId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Ranking ranking = rank(base, requester);
        Page p = page(ranking, page);
        return new RecommendationsResponse(String.valueOf(baseStoreId), ranking.baseTime(), ranking.baseTimeSource(),
                p.page(), p.pageCount(), p.cards(), p.publicData());
    }

    /**
     * 다음 가게 추천 알림(E8, CONTRACTS_API 3.11, FR-CRS-02): 예약 신청 커밋 뒤 새 트랜잭션에서 그 예약의 추천을 3.1과 같게
     * 계산하고(기준 가게 = 예약 가게, 기준 시각 = 예약 시각), 후보가 있으면 {@link NextStoreRecommendedEvent}를 내보낸다.
     * 알림은 C14 리스너가 이 트랜잭션 커밋 뒤에 만든다. 예약 · 가게가 없거나 보이지 않으면 아무것도 하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void announceNextStores(long reservationId) {
        Optional<RecommendationRepository.BaseReservation> reservation = repository.findReservation(reservationId);
        if (reservation.isEmpty()) {
            return;
        }
        RecommendationRepository.BaseReservation r = reservation.get();
        Optional<StoreInfo> base = repository.findVisibleStore(r.storeId());
        if (base.isEmpty()) {
            return;
        }
        // 회원은 같은 날 예약 제외 · 성별 · 연령대까지(3.1). 비회원은 지금 계산에서 식별하지 않는다
        Requester requester = repository.isMember(r.customerId()) ? new Requester(r.customerId()) : Requester.UNIDENTIFIED;
        Ranking ranking = rankAt(base.get(), requester, r.startAt().toInstant(ZoneOffset.UTC), BASE_TIME_RESERVATION);
        if (!ranking.all().isEmpty()) {
            publisher.publishEvent(new NextStoreRecommendedEvent(r.id(), r.storeId(), r.customerId()));
        }
    }

    /** 한 묶음: 설정값 recommend_count씩, page를 pageCount로 나눈 나머지(FR-CRS-06). 묶음에 지역 인기 가게가 있으면 공공데이터 출처. */
    record Page(int page, int pageCount, List<Card> cards, PublicData publicData) {
    }

    Page page(Ranking ranking, int page) {
        int size = settings.getInt(regionOf(ranking.base().id()), SettingKey.RECOMMEND_COUNT);
        int pageCount = (ranking.all().size() + size - 1) / size;
        int actualPage = pageCount == 0 ? 0 : page % pageCount;
        List<Ranked> slice = ranking.all().stream().skip((long) actualPage * size).limit(size).toList();
        PublicData publicData = slice.stream().anyMatch(r -> r.badge() == RecommendationBadge.LOCAL_POPULAR)
                ? repository.publicData().map(d -> new PublicData(d.source(), d.period())).orElse(null)
                : null;
        return new Page(actualPage, pageCount, slice.stream().map(RecommendationService::card).toList(), publicData);
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
        if (requester.memberCustomerId() != null) {
            Optional<LocalDateTime> upcoming = repository.nearestUpcomingReservation(requester.memberCustomerId(),
                    base.id(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            if (upcoming.isPresent()) {
                return rankAt(base, requester, upcoming.get().toInstant(ZoneOffset.UTC), BASE_TIME_RESERVATION);
            }
        }
        return rankAt(base, requester, now, BASE_TIME_NOW);
    }

    /** 정해진 기준 시각으로 매긴 전체 순위. 회원이면 같은 날 예약 제외 · 성별 · 연령대를 쓴다. */
    Ranking rankAt(StoreInfo base, Requester requester, Instant baseTime, String source) {
        CandidateRequester candidateRequester = CandidateRequester.UNIDENTIFIED;
        Profile profile = Profile.UNKNOWN;
        if (requester.memberCustomerId() != null) {
            candidateRequester = CandidateRequester.member(requester.memberCustomerId());
            profile = repository.memberProfile(requester.memberCustomerId());
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
