package com.dlab.integration.aligo;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.service.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 문자 채널 발송 (알리고).
 *
 * <p>심사가 없는 채널이라 <b>알림톡보다 먼저 열린다</b> — 카카오는 승인된 템플릿 코드만
 * 받지만 문자는 본문을 그대로 보낸다. 첫 사용처는 긴급 출입 2종(출입문 비밀번호 제공 ·
 * 07:05 출근 미확인)이다.
 *
 * <h2>★ 알림톡은 여기에 붙이지 말 것</h2>
 * 채널이 다르면 <b>구현체도 따로</b>다({@link #channel()} 이 키다). 알림톡은
 * 엔드포인트도({@code kakaoapi.aligo.in}) 성공 코드도({@code 0}, 문자는 {@code 1})
 * 다르고 <b>승인된 {@code tpl_code} 가 필수</b>라, 한 클래스에 섞으면 한쪽 규칙이 다른 쪽을
 * 조용히 깨뜨린다. 템플릿 코드가 나오면 {@code AligoAlimtalkSender} 를 새로 만든다.
 *
 * <h2>수신 번호는 계정이 가리키는 사람에게서 찾는다</h2>
 * {@code Account} 자체에는 전화번호가 없다 — 학생·학부모·행정·담당선생님 중 어느
 * 유형인지에 따라 다른 테이블에 있다. 번호가 없으면 <b>던진다</b>: 실패로 남아야
 * 「왜 안 왔지」에 답할 수 있고, 조용히 건너뛰면 발송 이력에 성공으로 남는다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${aligo.api-key:}' != ''")
@RequiredArgsConstructor
public class AligoNotificationSender implements NotificationSender {

    private final AligoClient client;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(NotificationLog log) {
        // ★ 엔티티가 풀어 준 번호를 쓴다. 계정에서만 꺼내면 계정 없는 수신자
        //   (입학 대기자·급식업체)에게 보내는 이력이 조용히 실패한다
        String phone = log.resolvedPhone();
        if (phone == null || phone.isBlank()) {
            throw new AligoException(0, "수신자 전화번호가 없습니다: logId=" + log.getId());
        }

        String body = log.getBody();
        // 길이로 우리가 유형을 정한다 — 벤더 자동 전환에 맡기면 단가가 말없이 바뀐다
        client.send(phone, log.getTitle(), body, AligoClient.MessageType.of(body));
    }

}
