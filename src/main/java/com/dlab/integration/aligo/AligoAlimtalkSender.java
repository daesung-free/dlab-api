package com.dlab.integration.aligo;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.service.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 카카오 알림톡 채널 발송 (알리고).
 *
 * <p>미등원·승인 같은 <b>놓치면 안 되는 알림</b>이 이 채널이다. 그전까지는 발송 구현체가
 * 문자 하나뿐이어서 {@code KAKAO_ALIMTALK} 템플릿이 <b>전부 문자로 나가고 있었다</b> —
 * 학부모가 받는 문구가 심사받은 것과 달라지는 상태였다.
 *
 * <h2>★ 문자와 한 클래스에 섞지 않는다</h2>
 * {@link NotificationSender#channel()} 이 발송기를 고르는 키라 <b>한 구현체가 두 채널을
 * 받을 수 없다.</b> 게다가 알림톡은 엔드포인트도({@code kakaoapi.aligo.in}) 성공 코드도
 * ({@code 0}, 문자는 {@code 1}) 파라미터 이름도 다르다 — 분기로 섞으면 한쪽 규칙이 다른
 * 쪽을 조용히 깨뜨린다. 벤더 호출은 {@link AligoAlimtalkClient} 가 맡는다.
 *
 * <h2>★★ 발신프로필 키가 없으면 빈 자체를 만들지 않는다</h2>
 * {@code senderkey} 없이는 알리고가 인증 오류를 돌려준다. 빈을 만들어 두면 발송마다
 * {@code FAILED} 이력이 쌓이는데, 빈이 없으면 발송 경로가
 * <b>{@code SKIPPED "발송 구현체 없음: KAKAO_ALIMTALK"}</b> 으로 남긴다 — 「설정이
 * 덜 됐다」와 「보냈는데 실패했다」가 구분된다.
 *
 * <h2>⚠️ 템플릿 코드는 승인된 것만 쓸 수 있다</h2>
 * 코드가 비어 있으면 {@code NotificationService} 가 먼저 건너뛴다(문자로 떨어뜨리지
 * 않는다 — SMS 폴백은 E-5 로 폐기됐다). 여기 도달했는데도 없으면 설정이 중간에 바뀐
 * 것이므로 <b>던진다.</b> 조용히 넘기면 실패가 성공으로 남는다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${aligo.api-key:}' != '' and '${aligo.sender-key:}' != ''")
@RequiredArgsConstructor
public class AligoAlimtalkSender implements NotificationSender {

    private final AligoAlimtalkClient client;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.KAKAO_ALIMTALK;
    }

    @Override
    public void send(NotificationLog log, NotificationTemplate template) {
        // ★ 계정 없는 수신자(입학 대기자·급식업체)도 있으므로 엔티티가 풀어 준 번호를 쓴다
        String phone = log.resolvedPhone();
        if (phone == null || phone.isBlank()) {
            throw new AligoException(0, "수신자 전화번호가 없습니다: logId=" + log.getId());
        }

        String tplCode = template.getKakaoTemplateCode();
        if (tplCode == null || tplCode.isBlank()) {
            // 발송 경로가 이미 걸렀어야 하는 경우다 — 실패로 남겨 원인이 보이게 한다
            throw new AligoException(0,
                    "알림톡 템플릿 코드가 없습니다: event=" + log.getEventCode());
        }

        client.send(phone, tplCode, log.getTitle(), log.getBody());
    }
}
