package com.alleycrm.recommend.score;

import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.DefaultScoreCalculator.Score;
import com.alleycrm.recommend.score.RecordScoreCalculator.Params;
import com.alleycrm.recommend.score.RecordScoreCalculator.Result;
import com.alleycrm.setting.SettingKey;
import com.alleycrm.setting.SettingService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 최종 점수(E6 #20, 05-policy 「다음 방문 추천」 2 ~ 5단계). 기본값 점수(E5)와 실제 기록 점수를 기록 양에 따라 섞고,
 * 손님의 성별 · 연령대 집단 점수를 섞는다. 순위 · 배지 · 이유 문구 · API(E7)가 이 결과를 쓴다. k · 최소 손님 수 · g는 권역 설정값.
 */
@Service
public class RecommendScoreService {

    /**
     * 후보 한 곳의 점수. {@code nA} · {@code nAB} · 기본값 항별 값은 배지 · 이유 문구 판단용이고 손님 응답에 넣지 않는다(FR-CRS-05).
     */
    public record RecommendScore(long storeId, double finalScore, double lift0, Double lift1, int nA, int nAB,
            boolean groupUsed, boolean visitDataBased, Map<DefaultScoreTerm, Double> defaultTerms) {
    }

    private final DefaultScoreService defaultScores;
    private final DefaultScoreRepository stores;
    private final VisitRecordRepository visits;
    private final SettingService settings;

    public RecommendScoreService(DefaultScoreService defaultScores, DefaultScoreRepository stores,
            VisitRecordRepository visits, SettingService settings) {
        this.defaultScores = defaultScores;
        this.stores = stores;
        this.visits = visits;
        this.settings = settings;
    }

    /**
     * 후보 가게들의 최종 점수(후보 순서 그대로). 기준 가게가 없거나 후보가 없으면 빈 목록.
     *
     * @param baseTime 기준 시각: 기준 가게를 예약했으면 그 예약 시각, 아니면 지금(FR-CRS-03 #4). 시간대 항에 쓴다
     */
    @Transactional(readOnly = true)
    public List<RecommendScore> score(long baseStoreId, List<Long> candidateStoreIds, Instant baseTime, Profile profile) {
        List<Score> defaults = defaultScores.score(baseStoreId, candidateStoreIds, baseTime, profile);
        if (defaults.isEmpty()) {
            return List.of();
        }
        long regionId = stores.findStores(List.of(baseStoreId)).getFirst().regionId();
        Map<Long, Double> lift0 = new LinkedHashMap<>();
        defaults.forEach(s -> lift0.put(s.storeId(), s.lift0()));
        Params params = new Params(
                settings.getDecimal(regionId, SettingKey.RECOMMEND_SMOOTHING_K).doubleValue(),
                settings.getInt(regionId, SettingKey.RECOMMEND_MIN_UNIQUE_CUSTOMERS),
                settings.getDecimal(regionId, SettingKey.RECOMMEND_GROUP_RATIO).doubleValue());
        Map<Long, Result> records = RecordScoreCalculator
                .score(baseStoreId, lift0, visits.findVisitsInRegion(regionId), profile, params).stream()
                .collect(Collectors.toMap(Result::storeId, Function.identity()));
        return defaults.stream().map(d -> {
            Result r = records.get(d.storeId());
            return new RecommendScore(d.storeId(), r.finalScore(), d.lift0(), r.lift1(), r.nA(), r.nAB(),
                    r.groupUsed(), r.visitDataBased(), d.terms());
        }).toList();
    }
}
