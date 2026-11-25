package com.alleycrm.seed;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 시드 · 시연 데이터를 한 번에 초기화하고 다시 넣는다(00-backlog 「같이 쓰는 것 규칙」, 담당 E).
 *
 * <pre>
 * # 검사만 (dry-run, 아무것도 지우지 않음)
 * .\gradlew.bat bootRun --args="--spring.profiles.active=seed"
 * # 로컬 DB를 지우고 다시 넣기
 * .\gradlew.bat bootRun --args="--spring.profiles.active=seed --seed.confirm=reset-all-data"
 * </pre>
 *
 * 넣는 순서: ① 초기화(참조 역순) → ② 권역 · 가게 15곳(E1) → ③ 상인회 계정(C2 단계) → ④ 공공데이터(E2)
 * → ⑤ 가상 데이터 1: 손님 · 예약 · 취소 · 방문(E16) → ⑥ 가상 데이터 2: 노출 · 코스 제안 · 소개 · 쿠폰 사례(E17).
 *
 * <p>초기화 · 적재:
 * <ul>
 *   <li><b>한 트랜잭션</b>({@link SeedLoader}): 중간에 실패하면 지운 것까지 모두 롤백한다. 그래서 MySQL {@code TRUNCATE}
 *       (암묵적 커밋)와 {@code ALTER TABLE ... AUTO_INCREMENT}를 쓰지 않고 {@code DELETE}로 지운다.
 *       ID는 실행마다 달라지므로 가상 데이터는 DB ID가 아니라 시드 코드(S01 등)로 가게를 가리킨다.</li>
 *   <li><b>참조 무결성</b>({@link SeedTableCleaner}): {@code FOREIGN_KEY_CHECKS}를 끄지 않고, 실제 DB의 외래 키에서
 *       지울 순서를 매번 계산한다. {@code flyway_schema_history}는 지우지 않는다.</li>
 *   <li><b>중복 실행</b>: MySQL 이름 잠금({@code GET_LOCK})으로 같은 DB에 실행기가 동시에 둘 돌면 뒤의 것은 바로 실패한다.
 *       차례로 다시 돌리면 매번 전부 지우고 같은 파일로 넣으므로 결과가 같다(ID 제외).</li>
 *   <li><b>주의</b>: 로컬 DB의 모든 데이터(직접 만든 계정 포함)를 지운다. 웹 서버가 같은 DB를 쓰는 중에는 돌리지 않는다.</li>
 * </ul>
 */
@Component
@Profile("seed")
@EnableConfigurationProperties(SeedProperties.class)
class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final Environment environment;
    private final DataSource dataSource;
    private final SeedProperties properties;
    private final SeedLoader loader;
    private final ConfigurableApplicationContext context;

    SeedRunner(Environment environment, DataSource dataSource, SeedProperties properties, SeedLoader loader,
            ConfigurableApplicationContext context) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.properties = properties;
        this.loader = loader;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
        if (properties.closeAfterRun()) {
            // 시드만 하고 끝나는 실행이라 웹 컨텍스트를 닫는다. 실패하면 예외로 앱이 뜨지 않고 0이 아닌 코드로 끝난다.
            SpringApplication.exit(context);
        }
    }

    private void seed() {
        SeedSafetyGuard.Mode mode = SeedSafetyGuard.decide(
                Arrays.asList(environment.getActiveProfiles()),
                properties.confirm(),
                jdbcUrl());

        SeedRegionData regionStores = SeedDataLoader.loadRegionStores();
        SeedDataValidator.validate(regionStores);
        logPlan(regionStores);

        if (mode == SeedSafetyGuard.Mode.DRY_RUN) {
            log.info("[seed] dry-run: 검사만 했고 DB는 바꾸지 않았습니다. 다시 넣으려면 --seed.confirm={} 를 주세요.",
                    SeedSafetyGuard.CONFIRM_TOKEN);
            return;
        }
        SeedLoader.Result result = loader.resetAndLoad(regionStores);
        log.info("[seed] 지운 표 {}개 {}", result.clearedTables().size(), result.clearedTables());
        log.info("[seed] 권역 1개, 가게 {}곳을 넣었습니다.", result.regionStores().storeIdsByCode().size());
    }

    private void logPlan(SeedRegionData data) {
        Map<SeedAlley, List<SeedRegionData.Store>> byAlley = data.stores().stream()
                .collect(Collectors.groupingBy(SeedRegionData.Store::alley));
        log.info("[seed] 권역 '{}', 참여 가게 {}곳", data.regionName(), data.stores().size());
        for (SeedAlley alley : SeedAlley.values()) {
            String stores = byAlley.getOrDefault(alley, List.of()).stream()
                    .sorted(Comparator.comparingInt(SeedRegionData.Store::mapX))
                    .map(s -> s.code() + " " + s.name() + "(" + s.category().label() + ")")
                    .collect(Collectors.joining(", "));
            log.info("[seed]  {}: {}", alley.label(), stores);
        }
    }

    private String jdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("시드 대상 DB에 연결하지 못했습니다.", e);
        }
    }
}
