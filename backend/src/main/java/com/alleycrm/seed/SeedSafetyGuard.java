package com.alleycrm.seed;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * 시드 실행기가 데이터를 지워도 되는지 정한다. 실수로 일반 · 운영 DB를 지우지 않게 하는 안전장치다.
 *
 * <ol>
 *   <li>실행기 자체가 {@code seed} 프로필에서만 켜진다({@link SeedRunner}).</li>
 *   <li>{@code prod} · {@code production} 프로필이 함께 켜져 있으면 무조건 거부한다.</li>
 *   <li>확인 값 {@code seed.confirm=reset-all-data}가 없으면 dry-run(검사 · 계획 출력만)이다.</li>
 *   <li>지우려는 DB가 로컬(localhost · 127.0.0.1 · ::1 · compose 서비스 {@code mysql})이 아니면 거부한다.
 *       원격 DB를 지우는 우회 옵션은 두지 않는다. 배포 구성이 정해지지 않았고(ARCHITECTURE 「미결」),
 *       원격 시연 DB를 다시 넣어야 할 일이 생기면 그때 근거와 함께 따로 정한다.</li>
 * </ol>
 */
public final class SeedSafetyGuard {

    public static final String CONFIRM_TOKEN = "reset-all-data";

    static final Set<String> BLOCKED_PROFILES = Set.of("prod", "production");
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "mysql");

    public enum Mode {
        /** 검사하고 할 일을 출력만 한다. DB를 바꾸지 않는다. */
        DRY_RUN,
        /** 시드 · 시연 데이터를 지우고 다시 넣는다. */
        RESET_AND_LOAD
    }

    private SeedSafetyGuard() {
    }

    /**
     * @throws IllegalStateException 지우면 안 되는 환경일 때
     */
    public static Mode decide(Collection<String> activeProfiles, String confirm, String jdbcUrl) {
        for (String profile : activeProfiles) {
            if (BLOCKED_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("운영 프로필(" + profile + ")에서는 시드 실행기를 쓸 수 없습니다.");
            }
        }
        if (!CONFIRM_TOKEN.equals(confirm)) {
            return Mode.DRY_RUN;
        }
        String host = hostOf(jdbcUrl);
        if (!LOCAL_HOSTS.contains(host)) {
            throw new IllegalStateException("로컬이 아닌 DB(" + host + ")는 시드 실행기로 지우지 않습니다.");
        }
        return Mode.RESET_AND_LOAD;
    }

    /** {@code jdbc:mysql://host:3306/db?...}에서 host만 꺼낸다. 읽지 못하면 빈 문자열(= 로컬 아님). */
    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return "";
        }
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
