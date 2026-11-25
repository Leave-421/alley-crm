package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.alleycrm.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** 일반 실행(seed 프로필 없음)에서는 시드 실행기가 켜지지 않는다(Testcontainers, Docker 필요). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeedRunnerInactiveTest {

    @Autowired
    ApplicationContext context;

    @Test
    void 일반_실행에는_시드_실행기가_없다() {
        assertThat(context.getBeansOfType(SeedRunner.class)).isEmpty();
    }
}
