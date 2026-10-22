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
 *
 * <h2>⚠️⚠️ 같은 일이 2026-10-22 에 다시 났다 — 운영 크래시 루프</h2>
 * {@link AligoAlimtalkClient} 가 나중에 같은 모양으로 추가됐는데 <b>이 테스트의
 * {@code @Import} 에 들어가지 않았다.</b> 위 경고가 바로 윗줄에 적혀 있었는데도 그랬다.
 *
 * <p>★ <b>그래서 「새 빈을 여기 넣는 것」이 이 테스트의 유지 규칙이다.</b>
 * {@code aligo.*} 나 {@code premium.*} 처럼 <b>키가 있을 때만 만들어지는 빈</b>을
 * 추가하면 반드시 {@code @Import} 에 같이 넣을 것 — 넣지 않으면
 * <b>로컬·CI 는 전부 통과하고 운영에서만 터진다.</b>
 *
 * <p>⚠️ 클래스패스를 훑어 자동으로 검사하는 방법을 시도했으나 <b>안 된다.</b>
 * {@code ClassPathScanningCandidateComponentProvider} 가 {@code @Conditional} 을
 * 평가해서 <b>바로 그 빈들을 스캔에서 제외</b>한다 — 검사하려는 대상이 조건 때문에
 * 빠지는 것이고, <b>버그가 운영까지 간 것과 같은 이유</b>다.
 */
class AligoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AligoBeans.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AligoProperties.class)
    @Import({AligoClient.class, AligoAlimtalkClient.class,
            AligoSmsSender.class, AligoNotificationSender.class,
            AligoAlimtalkSender.class, LoggingSmsSender.class})
    static class AligoBeans {
    }

    @Test
    @DisplayName("★★ 키가 있으면 빈이 만들어진다 — 생성자가 둘이라 기동이 깨진 적이 있다")
    void beansAreCreatedWhenKeyIsPresent() {
        runner.withPropertyValues(
                        "aligo.api-key=test-key",
                        "aligo.user-id=dlab",
                        "aligo.sender-phone=0261058090",
                        "aligo.sender-key=profile-key",
                        "aligo.test-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AligoClient.class);
                    // ★★ 알림톡도 같이 본다. 이 한 줄이 없어서 2026-10-22 운영 배포가
                    //    크래시 루프로 실패했다 — AligoAlimtalkClient 가 AligoClient 와
                    //    똑같이 생성자 둘인데 @Autowired 가 빠져 있었다.
                    //    ⚠️ 아래 javadoc 의 함정이 **같은 패키지에서 두 번** 났다.
                    assertThat(context).hasSingleBean(AligoAlimtalkClient.class);
                    assertThat(context).hasSingleBean(AligoNotificationSender.class);
                    // ★ 알림톡 발송기는 발신프로필 키까지 있어야 올라온다 — 아래 테스트 참고
                    assertThat(context).hasSingleBean(AligoAlimtalkSender.class);
                    // ★ 목업이 물러나고 실제 발송기가 SmsSender 자리를 차지한다
                    assertThat(context.getBean(SmsSender.class)).isInstanceOf(AligoSmsSender.class);
                });
    }

    @Test
    @DisplayName("★★ 발신프로필 키가 없으면 알림톡 발송기를 만들지 않는다 — 실패 이력만 쌓인다")
    void alimtalkSenderNeedsSenderKey() {
        // senderkey 없이 호출하면 알리고가 인증 오류를 준다. 빈을 만들어 두면 발송마다
        // FAILED 가 쌓이는데, 빈이 없으면 「발송 구현체 없음」으로 SKIPPED 가 남아
        // 「설정이 덜 됐다」와 「보냈는데 실패했다」가 구분된다.
        runner.withPropertyValues(
                        "aligo.api-key=test-key",
                        "aligo.user-id=dlab",
                        "aligo.sender-phone=0261058090",
                        "aligo.test-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AligoNotificationSender.class);
                    assertThat(context).doesNotHaveBean(AligoAlimtalkSender.class);
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
