package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.seed.SeedSafetyGuard.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 일반 · 운영 환경에서 데이터가 지워지지 않는지. */
class SeedSafetyGuardTest {

    private static final String LOCAL = "jdbc:mysql://localhost:3306/alley_crm?serverTimezone=UTC";
    private static final String REMOTE = "jdbc:mysql://db.example.com:3306/alley_crm";

    @Test
    void 확인_값이_없으면_dry_run() {
        assertThat(SeedSafetyGuard.decide(List.of("seed"), "", LOCAL)).isEqualTo(Mode.DRY_RUN);
        assertThat(SeedSafetyGuard.decide(List.of("seed"), null, LOCAL)).isEqualTo(Mode.DRY_RUN);
        assertThat(SeedSafetyGuard.decide(List.of("seed"), "yes", LOCAL)).isEqualTo(Mode.DRY_RUN);
    }

    @Test
    void 확인_값이_없으면_원격_DB여도_dry_run이라_아무것도_지우지_않는다() {
        assertThat(SeedSafetyGuard.decide(List.of("seed"), "", REMOTE)).isEqualTo(Mode.DRY_RUN);
    }

    @Test
    void 로컬_DB와_확인_값이면_초기화() {
        assertThat(SeedSafetyGuard.decide(List.of("seed"), SeedSafetyGuard.CONFIRM_TOKEN, LOCAL))
                .isEqualTo(Mode.RESET_AND_LOAD);
        assertThat(SeedSafetyGuard.decide(List.of("seed"), SeedSafetyGuard.CONFIRM_TOKEN,
                "jdbc:mysql://127.0.0.1:13306/x")).isEqualTo(Mode.RESET_AND_LOAD);
        assertThat(SeedSafetyGuard.decide(List.of("seed"), SeedSafetyGuard.CONFIRM_TOKEN,
                "jdbc:mysql://mysql:3306/x")).isEqualTo(Mode.RESET_AND_LOAD);
    }

    @Test
    void 운영_프로필이_켜져_있으면_확인_값이_없어도_거부() {
        assertThatThrownBy(() -> SeedSafetyGuard.decide(List.of("seed", "prod"), "", LOCAL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SeedSafetyGuard.decide(List.of("PRODUCTION", "seed"), SeedSafetyGuard.CONFIRM_TOKEN, LOCAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 로컬이_아닌_DB는_확인_값이_있어도_거부_우회_옵션_없음() {
        assertThatThrownBy(() -> SeedSafetyGuard.decide(List.of("seed"), SeedSafetyGuard.CONFIRM_TOKEN, REMOTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db.example.com");
    }

    @Test
    void 주소를_읽지_못하면_로컬이_아닌_것으로_본다() {
        assertThat(SeedSafetyGuard.hostOf(null)).isEmpty();
        assertThat(SeedSafetyGuard.hostOf("not a url")).isEmpty();
        assertThatThrownBy(() -> SeedSafetyGuard.decide(List.of("seed"), SeedSafetyGuard.CONFIRM_TOKEN, "???"))
                .isInstanceOf(IllegalStateException.class);
    }
}
