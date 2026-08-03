package com.dlab.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Redis도 클래스패스에 있어 Spring Data가 리포지토리 소속을 추측하려 든다.
 * 도메인 리포지토리는 전부 JPA이므로 명시적으로 지정한다.
 */
@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.dlab.domain")
public class JpaAuditingConfig {
}
