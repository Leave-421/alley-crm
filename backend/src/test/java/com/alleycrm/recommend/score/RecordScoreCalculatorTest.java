package com.alleycrm.recommend.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.alleycrm.recommend.score.DefaultScoreCalculator.Profile;
import com.alleycrm.recommend.score.RecordScoreCalculator.Params;
import com.alleycrm.recommend.score.RecordScoreCalculator.Result;
import com.alleycrm.recommend.score.RecordScoreCalculator.Visit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실제 기록 점수 · 섞기 · 집단(E6, 05-policy 3 ~ 5단계, FR-CRS-03 · 04). 인수 조건의 숫자 그대로 확인한다. */
class RecordScoreCalculatorTest {

    private static final long A = 1;
    private static final long B = 2;
    private static final long C = 3;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    /** k = 10, 최소 고유 손님 5명, g = 0.3(05-policy 「추천 설정값」). */
    private static final Params PARAMS = new Params(10, 5, 0.3);
    private static final double LIFT0 = 0.8;

    private final List<Visit> visits = new ArrayList<>();
    private long nextCustomer = 1;

    /** 손님 한 명이 같은 날 가게들을 이 순서로 방문. */
    private void day(String gender, String age, long... stores) {
        long customer = nextCustomer++;
        for (int i = 0; i < stores.length; i++) {
            visits.add(new Visit(customer, stores[i], DAY, i, gender, age));
        }
    }

    private void many(int count, String gender, String age, long... stores) {
        for (int i = 0; i < count; i++) {
            day(gender, age, stores);
        }
    }

    private Result score(Profile profile) {
        return RecordScoreCalculator.score(A, Map.of(B, LIFT0), visits, profile, PARAMS).getFirst();
    }

    @Test
    void 같이_간_비율과_기대_대비_비율_FR_CRS_03_1() {
        // A 방문 100명 중 20명이 B로 이어 감, B 방문 고유 손님 20명 / 권역 200명 = 10%
        many(20, null, null, A, B);
        many(80, null, null, A);
        many(100, null, null, C);

        Result r = score(Profile.UNKNOWN);

        assertThat(r.nA()).isEqualTo(100);
        assertThat(r.nAB()).isEqualTo(20);
        assertThat(r.lift1()).isCloseTo(2.0, within(1e-9)); // (20 ÷ 100) ÷ 0.1
        assertThat(r.finalScore()).isCloseTo((100 * 2.0 + 10 * LIFT0) / 110, within(1e-9));
    }

    @Test
    void 이어_간_손님이_4명이면_기본값만_FR_CRS_04_1() {
        many(4, null, null, A, B);
        many(20, null, null, A);

        Result r = score(Profile.UNKNOWN);

        assertThat(r.nAB()).isEqualTo(4);
        assertThat(r.lift1()).isNull();
        assertThat(r.finalScore()).isEqualTo(LIFT0);
        assertThat(r.visitDataBased()).isFalse();
    }

    @Test
    void A_10명_중_5명이_B로_가면_실제_기록과_기본값을_반반_섞는다_FR_CRS_04_2() {
        many(5, null, null, A, B);
        many(5, null, null, A);

        Result r = score(Profile.UNKNOWN);

        assertThat(r.nA()).isEqualTo(10);
        assertThat(r.finalScore()).isCloseTo((r.lift1() + LIFT0) / 2, within(1e-9)); // k = n_A = 10
        assertThat(r.visitDataBased()).isTrue(); // n_AB ≥ 5, n_A ≥ k
    }

    @Test
    void 집단에서_7명이_이어_가면_전체_70퍼센트_집단_30퍼센트_FR_CRS_03_2() {
        many(7, "female", "age_20_or_under", A, B);
        many(13, "female", "age_20_or_under", A);
        many(10, "male", "age_30s", A, B);
        many(20, "male", "age_30s", A, C);
        Profile profile = new Profile("female", "age_20_or_under");

        Result r = score(profile);

        double overall = score(Profile.UNKNOWN).finalScore();
        List<Visit> groupOnly = visits.stream().filter(v -> "female".equals(v.gender())).toList();
        double group = RecordScoreCalculator.score(A, Map.of(B, LIFT0), groupOnly, Profile.UNKNOWN, PARAMS)
                .getFirst().finalScore();
        assertThat(r.groupUsed()).isTrue();
        assertThat(r.finalScore()).isCloseTo(0.7 * overall + 0.3 * group, within(1e-9));
    }

    @Test
    void 집단에서_3명뿐이면_전체_점수만_FR_CRS_03_3() {
        many(3, "female", "age_20_or_under", A, B);
        many(10, "male", "age_30s", A, B);
        many(10, "male", "age_30s", A);

        Result r = score(new Profile("female", "age_20_or_under"));

        assertThat(r.groupUsed()).isFalse();
        assertThat(r.finalScore()).isCloseTo(score(Profile.UNKNOWN).finalScore(), within(1e-9));
    }

    @Test
    void 같은_날_바로_다음_방문만_이어_간_것으로_센다() {
        many(6, null, null, A, C, B);   // A 다음은 C
        many(6, null, null, B, A);      // B → A(반대 방향)
        visits.add(new Visit(999, A, DAY, 0, null, null));
        visits.add(new Visit(999, B, DAY.plusDays(1), 0, null, null)); // 다른 날

        assertThat(score(Profile.UNKNOWN).nAB()).isZero();
    }

    @Test
    void 같은_손님이_여러_번_이어_가도_고유_손님으로_센다() {
        for (int i = 0; i < 3; i++) {
            visits.add(new Visit(1, A, DAY.plusDays(i), 0, null, null));
            visits.add(new Visit(1, B, DAY.plusDays(i), 1, null, null));
        }

        Result r = score(Profile.UNKNOWN);

        assertThat(r.nA()).isEqualTo(1);
        assertThat(r.nAB()).isEqualTo(1);
    }

    @Test
    void 방문_데이터_기반_배지는_n_AB_5명_이상이고_n_A가_k_이상일_때() {
        many(5, null, null, A, B);
        many(2, null, null, A);

        Result r = score(Profile.UNKNOWN);

        assertThat(r.nAB()).isEqualTo(5);
        assertThat(r.nA()).isEqualTo(7);
        assertThat(r.lift1()).isNotNull();    // 실제 기록은 섞지만
        assertThat(r.visitDataBased()).isFalse(); // 비중이 절반이 안 됨(n_A < k)
    }
}
