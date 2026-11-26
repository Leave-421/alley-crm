package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * seed 프로필을 확인 값 없이 켜면 dry-run으로 검사만 하고 정상 기동한다(Testcontainers, Docker 필요).
 * 실제 초기화 · 적재 통합 테스트는 D1(#12) 스키마가 머지된 뒤 더한다.
 */
@SpringBootTest(properties = "seed.close-after-run=false")
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedRunnerDryRunTest {

    @Autowired
    ApplicationContext context;

    @Test
    void 확인_값이_없으면_dry_run으로_끝나_컨텍스트가_뜬다() {
        // 실행기가 예외를 던졌다면 컨텍스트가 뜨지 않아 이 테스트에 오지 못한다.
        assertThat(context.getBeansOfType(SeedRunner.class)).hasSize(1);
    }
}
