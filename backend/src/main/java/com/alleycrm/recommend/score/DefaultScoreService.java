package com.alleycrm.recommend.score;

import com.alleycrm.recommend.domain.TimeBand;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Base;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Candidate;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Moment;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Score;
import com.alleycrm.reservation.request.SlotRules;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공공데이터 기본값 점수(E5 #17, 05-policy 「다음 방문 추천」 2단계). 후보(E4)를 받아 항별 점수 · {@code s0} · {@code lift0}을 돌려준다.
 * 실제 기록 점수 · 섞기(E6)와 순위 · 배지 · 이유 문구(E7)가 이 결과를 쓴다. 가중치 · 거리 지수 β는 기준 가게 권역의 설정값이다(NFR-04).
 */
@Service
public class DefaultScoreService {

    private final DefaultScoreRepository repository;
    private final SettingService settings;

    public DefaultScoreService(DefaultScoreRepository repository, SettingService settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /**
     * 후보 가게들의 기본값 점수(후보 순서 그대로). 기준 가게가 없거나 후보가 없으면 빈 목록.
     *
     * @param baseTime 기준 시각: 지도 추천이면 지금, 다음 코스 추천이면 기준 가게 예약 시각(05-policy). 요일 · 시간대는 운영 시간대로 본다
     * @param profile 손님의 성별 · 연령대(세션으로 정함). 모르면 {@link Profile#UNKNOWN} — 손님층 항을 빼고 가중치를 다시 맞춘다
     */
    @Transactional(readOnly = true)
    public List<Score> score(long baseStoreId, List<Long> candidateStoreIds, Instant baseTime, Profile profile) {
        List<Long> ids = new ArrayList<>(candidateStoreIds);
        ids.add(baseStoreId);
        Map<Long, DefaultScoreRepository.StoreRow> stores = repository.findStores(ids).stream()
                .collect(Collectors.toMap(DefaultScoreRepository.StoreRow::id, Function.identity()));
        DefaultScoreRepository.StoreRow base = stores.get(baseStoreId);
        if (base == null || candidateStoreIds.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = candidateStoreIds.stream()
                .map(stores::get)
                .filter(s -> s != null)
                .map(s -> new Candidate(s.id(), s.category(), s.x(), s.y()))
                .toList();
        LocalDateTime local = LocalDateTime.ofInstant(baseTime, SlotRules.SERVICE_ZONE);
        Moment moment = new Moment(local.getDayOfWeek(), TimeBand.of(local.toLocalTime()).next());
        return DefaultScoreCalculator.score(new Base(base.category(), base.x(), base.y()), candidates, moment, profile,
                repository.loadPublicStats(), weights(base.regionId()),
                settings.getDecimal(base.regionId(), SettingKey.RECOMMEND_DISTANCE_EXPONENT).doubleValue());
    }

    private Map<DefaultScoreTerm, Double> weights(long regionId) {
        Map<DefaultScoreTerm, Double> weights = new EnumMap<>(DefaultScoreTerm.class);
        for (DefaultScoreTerm term : DefaultScoreTerm.values()) {
            weights.put(term, settings.getDecimal(regionId, term.weightKey()).doubleValue());
        }
        return weights;
    }
}
