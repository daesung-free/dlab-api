package com.dlab.integration.aligo;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.verification.LoggingSmsSender;
import com.dlab.common.verification.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 알리고 빈이 실제로 만들어지는가.
 *
 * <h2>★★ 이 테스트가 없어서 「빌드·테스트 통과, 기동 실패」가 났다</h2>
 * {@link AligoClient}에 생성자가 둘(운영용 · 테스트 이음새)인데 {@code @Autowired}가 없어
 * Spring 이 고르지 못하고 <b>기본 생성자를 찾다가 {@code NoSuchMethodException}</b> 으로
 * 터졌다. 그런데 다른 테스트는 모두
 * <ul>
 *   <li>객체를 <b>직접 생성</b>해서(이음새) 이 경로를 타지 않고,</li>
 *   <li>{@code aligo.api-key}가 없어 <b>빈 자체가 안 만들어진다</b></li>
 * </ul>
 * 그래서 통과했다. <b>기동만이 이걸 잡는다</b> — CI 의 「Flyway 검증(빈 DB)」이 먼저 잡았다.
 */
class AligoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AligoBeans.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AligoProperties.class)
    @Import({AligoClient.class, AligoSmsSender.class, AligoNotificationSender.class,
            LoggingSmsSender.class})
    static class AligoBeans {
    }

    @Test
    @DisplayName("★★ 키가 있으면 빈이 만들어진다 — 생성자가 둘이라 기동이 깨진 적이 있다")
    void beansAreCreatedWhenKeyIsPresent() {
        runner.withPropertyValues(
                        "aligo.api-key=test-key",
                        "aligo.user-id=dlab",
                        "aligo.sender-phone=0261058090",
                        "aligo.test-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AligoClient.class);
                    assertThat(context).hasSingleBean(AligoNotificationSender.class);
                    // ★ 목업이 물러나고 실제 발송기가 SmsSender 자리를 차지한다
                    assertThat(context.getBean(SmsSender.class)).isInstanceOf(AligoSmsSender.class);
                });
    }

    @Test
    @DisplayName("키가 없으면 목업이 그대로 남는다")
    void mockRemainsWithoutKey() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AligoClient.class);
            assertThat(context.getBean(SmsSender.class)).isInstanceOf(LoggingSmsSender.class);
        });
    }

    @Test
    @DisplayName("★★ 키가 빈 문자열이어도 꺼진 것으로 본다 — 통과시키면 인증번호가 아무에게도 안 간다")
    void emptyKeyIsTreatedAsDisabled() {
        // ⚠️ @ConditionalOnProperty 는 **키가 있는가**로만 판정해서 빈 문자열에도 통과한다.
        //    application.yml 기본값이 `${ALIGO_API_KEY:}` 라 키를 안 넣은 환경이 전부 여기 걸린다 —
        //    실제 발송기가 올라오고 목업이 @ConditionalOnMissingBean 으로 물러나서
        //    **문자가 안 나가는데 API 는 200** 이 된다(목업과 똑같은 모양이라 알아채기 어렵다)
        runner.withPropertyValues("aligo.api-key=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AligoClient.class);
            assertThat(context.getBean(SmsSender.class)).isInstanceOf(LoggingSmsSender.class);
        });
    }
}
