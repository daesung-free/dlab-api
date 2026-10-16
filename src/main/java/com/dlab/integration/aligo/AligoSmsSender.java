package com.dlab.integration.aligo;

import com.dlab.common.verification.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 인증번호 문자 발송 (알리고).
 *
 * <h2>★ 빈 이름이 {@code realSmsSender} 여야 한다</h2>
 * 목업({@code LoggingSmsSender})이 {@code @ConditionalOnMissingBean(name = "realSmsSender")}
 * 로 물러나게 되어 있다. 이름을 바꾸면 <b>둘 다 등록되거나 목업이 이겨서</b> 운영에서
 * 문자가 로그로만 찍히는데 API 는 200 을 준다.
 *
 * <h2>문구를 템플릿으로 두지 않는다</h2>
 * 인증번호는 <b>알림 도메인을 타지 않는다</b> — 가입 전이라 수신자 계정이 없고
 * ({@code notification_log.recipient_account_id} 가 NOT NULL), 수신 동의와도 무관하다.
 * 문구가 한 줄이라 여기 둔다.
 *
 * <p>⚠️ 90바이트를 넘지 않게 짧게 유지한다. 넘으면 LMS 로 가서 단가가 오른다.
 */
@Slf4j
@Component("realSmsSender")
@ConditionalOnExpression("'${aligo.api-key:}' != ''")
@RequiredArgsConstructor
public class AligoSmsSender implements SmsSender {

    private static final String FORM = "[디랩] 인증번호 %s 를 입력해 주세요.";

    private final AligoClient client;

    @Override
    public void sendVerificationCode(String phone, String code) {
        // 실패를 던진다 — 삼키면 사용자가 오지 않는 문자를 기다린다(SmsSender 규약)
        client.send(phone, null, FORM.formatted(code), AligoClient.MessageType.SMS);
    }
}
