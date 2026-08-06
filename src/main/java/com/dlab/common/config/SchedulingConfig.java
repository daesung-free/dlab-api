package com.dlab.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화.
 *
 * <p><b>{@code scheduling.enabled=false}로 끌 수 있다.</b> CI에서 기동만 확인할 때
 * 배치가 도는 것을 막기 위해서다 — 미등원 감지·결석 확정 같은 배치가 빈 DB에서 돌면
 * 검증하려던 것(마이그레이션 적용)과 무관한 로그·실패가 섞인다.
 *
 * <p>기본값은 {@code true}이므로 로컬·운영은 설정을 넣지 않아도 그대로 동작한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
