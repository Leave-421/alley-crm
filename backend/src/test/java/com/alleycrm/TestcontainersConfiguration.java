package com.alleycrm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 DB: 실제 MySQL 8.4를 Testcontainers로 띄운다(H2로 대신하지 않는다).
 * 버전은 루트 compose.yaml과 같게 둔다. Flyway 마이그레이션이 매번 적용된다.
 * 통합 테스트는 {@code @Import(TestcontainersConfiguration.class)}로 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4.13"));
	}

}
