package com.alleycrm.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시드 실행기 설정. 값은 실행할 때 인자로만 준다(설정 파일에 확인 값을 적어 두지 않는다).
 *
 * @param confirm {@link SeedSafetyGuard#CONFIRM_TOKEN}과 같을 때만 데이터를 지우고 다시 넣는다
 */
@ConfigurationProperties("seed")
public record SeedProperties(String confirm) {
}
