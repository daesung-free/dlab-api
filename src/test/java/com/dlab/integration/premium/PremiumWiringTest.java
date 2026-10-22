package com.dlab.integration.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 더프리미엄 빈이 실제로 만들어지는가.
 *
 * <h2>★★ {@code AligoWiringTest} 와 같은 이유로 있다</h2>
 * {@link PremiumReportClient} 도 생성자가 둘(운영용 · 테스트 이음새)이라
 * {@code @Autowired} 가 없으면 Spring 이 고르지 못하고 <b>기본 생성자를 찾다가
 * {@code NoSuchMethodException} 으로 기동이 실패한다.</b>
 *
 * <p>⚠️ 그리고 그것을 <b>다른 테스트는 잡지 못한다.</b>
 * <ul>
 *   <li>{@code PremiumReportClientTest} 는 <b>이음새 생성자를 직접 {@code new}</b> 로
 *       부른다 — Spring 주입 경로를 타지 않는다</li>
 *   <li>이 빈이 {@code @ConditionalOnProperty} 라 <b>키가 없으면 빈 자체가 안 만들어진다.</b>
 *       로컬·CI 에는 키가 없다</li>
 * </ul>
 *
 * <p>★ <b>즉 운영에서만 터진다.</b> 알리고 쪽에서 <b>실제로 두 번</b> 그렇게 났다
 * (두 번째가 2026-10-22 운영 크래시 루프다). 같은 모양이 여기에도 있어 같이 막는다.
 *
 * <h2>⚠️ 자동 검사는 안 된다</h2>
 * 클래스패스를 훑는 방식을 시도했으나
 * {@code ClassPathScanningCandidateComponentProvider} 가 {@code @Conditional} 을 평가해
 * <b>바로 그 빈들을 스캔에서 제외</b>한다 — 검사 대상이 조건 때문에 빠지는 것이고,
 * 버그가 운영까지 간 것과 같은 이유다. 그래서 <b>빈마다 이렇게 명시</b>한다.
 */
class PremiumWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PremiumBeans.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PremiumProperties.class)
    @Import(PremiumReportClient.class)
    static class PremiumBeans {

        /** 클라이언트가 서명 시각에 쓴다. */
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Test
    @DisplayName("★★ 키가 있으면 빈이 만들어진다 — 생성자가 둘이라 기동이 깨질 수 있다")
    void beanIsCreatedWhenKeysArePresent() {
        runner.withPropertyValues(
                        "premium.report.base-url=https://example.test",
                        "premium.report.api-key=test-key",
                        "premium.report.secret-key=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PremiumReportClient.class);
                });
    }

    @Test
    @DisplayName("키가 없으면 빈이 만들어지지 않는다 — 연동 전까지 아무 일도 하지 않는다")
    void beanIsAbsentWithoutKeys() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PremiumReportClient.class);
        });
    }

    @Test
    @DisplayName("★ 셋 중 하나만 빠져도 만들어지지 않는다 — 반쪽 설정으로 호출하면 401 을 쫓는다")
    void partialConfigurationDoesNotCreateBean() {
        runner.withPropertyValues(
                        "premium.report.base-url=https://example.test",
                        "premium.report.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PremiumReportClient.class);
                });
    }
}
