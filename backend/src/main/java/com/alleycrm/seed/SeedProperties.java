package com.alleycrm.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시드 실행기 설정. 확인 값은 실행할 때 인자로만 준다(설정 파일에 적어 두지 않는다).
 *
 * @param confirm {@link SeedSafetyGuard#CONFIRM_TOKEN}과 같을 때만 데이터를 지우고 다시 넣는다
 * @param closeAfterRun 시드가 끝나면 앱을 닫을지. 명령줄 실행은 {@code true}, 같은 컨텍스트를 계속 쓰는 테스트는 {@code false}
 */
@ConfigurationProperties("seed")
public record SeedProperties(String confirm, boolean closeAfterRun) {
}
