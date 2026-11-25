package com.alleycrm.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 서버 코드가 현재 시각을 직접 부르지 않는지 확인한다(ARCHITECTURE 「서버 시각」).
 * {@code Instant.now()} · {@code LocalDateTime.now()}처럼 인자 없는 now()를 찾으면 실패한다.
 * 시계를 주입받아 {@code Instant.now(clock)}로 쓴다.
 */
class NoDirectNowTest {

    private static final Pattern DIRECT_NOW = Pattern.compile(
            "\\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\\.now\\(\\s*\\)"
                    + "|System\\.currentTimeMillis\\(\\s*\\)"
                    + "|Clock\\.system(UTC|DefaultZone)\\(\\s*\\)");

    @Test
    void main_소스에_시계_없는_now_호출이_없다() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> hits;
        try (Stream<Path> files = Files.walk(main)) {
            hits = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("AppClock.java"))
                    .flatMap(NoDirectNowTest::matches)
                    .toList();
        }
        assertThat(hits).as("시계를 주입받아 now(clock)으로 쓰세요").isEmpty();
    }

    private static Stream<String> matches(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> DIRECT_NOW.matcher(lines.get(i)).find())
                    .mapToObj(i -> file + ":" + (i + 1) + "  " + lines.get(i).trim());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
