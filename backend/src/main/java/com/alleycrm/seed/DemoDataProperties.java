package com.alleycrm.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 가상 데이터 설정(E16 · E17). 가상 손님 수는 기획에서 미결이라(NFR-07, 03-requirements 남은 것) 설정값으로 둔다.
 *
 * @param customers 가상 손님 수. 팀 결정 전 임시 기본값
 * @param randomSeed 같은 값이면 같은 가상 데이터가 나온다(시연 재현)
 */
@ConfigurationProperties("seed.demo")
public record DemoDataProperties(
        @DefaultValue("600") int customers,
        @DefaultValue("20261211") long randomSeed) {
}
