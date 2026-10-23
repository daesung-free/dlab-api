package com.dlab.integration.aligo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.entity.RecipientType;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 알림톡 채널 발송기.
 *
 * <h2>★★ 이 클래스가 없던 동안 알림톡 템플릿이 전부 문자로 나갔다</h2>
 * 발송기가 문자 하나뿐이면 {@code KAKAO_ALIMTALK} 템플릿도 그 구현체가 받는다 —
 * 심사받은 문구와 다른 문자가 학부모에게 간다. 그래서 <b>채널이 키</b>라는 것을
 * 테스트로 묶는다.
 */
class AligoAlimtalkSenderTest {

    private final AligoAlimtalkClient client = mock(AligoAlimtalkClient.class);
    private final AligoAlimtalkSender sender = new AligoAlimtalkSender(client);

    private NotificationTemplate template(String tplCode) {
        NotificationTemplate t = new NotificationTemplate(NotificationEvent.MISSING_ATTENDANCE,
                NotificationChannel.KAKAO_ALIMTALK, RecipientType.PARENT, "studentName");
        ReflectionTestUtils.setField(t, "kakaoTemplateCode", tplCode);
        return t;
    }

    private NotificationLog toPhone(String phone) {
        return NotificationLog.toPhone(null, (short) 2026, NotificationEvent.MISSING_ATTENDANCE,
                NotificationChannel.KAKAO_ALIMTALK, phone, "홍길동 학부모", null,
                "[홍길동] 미등원", "홍길동 학생이 등원하지 않았습니다.", Map.of(), null);
    }

    @Test
    @DisplayName("★ 알림톡 채널을 맡는다 — 이 값이 발송기를 고르는 키다")
    void handlesAlimtalkChannel() {
        Assertions.assertThat(sender.channel()).isEqualTo(NotificationChannel.KAKAO_ALIMTALK);
    }

    @Test
    @DisplayName("★★ 템플릿의 승인된 tpl_code 로 알림톡 API 를 호출한다 — 문자가 아니다")
    void sendsThroughAlimtalkClient() {
        sender.send(toPhone("010-1234-5678"), template("UM_8230"));

        verify(client).send(eq("010-1234-5678"), eq("UM_8230"), eq("[홍길동] 미등원"),
                eq("홍길동 학생이 등원하지 않았습니다."));
    }

    @Test
    @DisplayName("★ 템플릿 코드가 없으면 던진다 — 조용히 넘기면 안 간 알림이 성공으로 남는다")
    void throwsWithoutTemplateCode() {
        // 발송 경로(NotificationService)가 먼저 SKIPPED 로 걸러야 하는 경우다.
        // 여기까지 왔다면 설정이 중간에 바뀐 것이므로 실패 이력으로 남긴다.
        assertThatThrownBy(() -> sender.send(toPhone("01012345678"), template(null)))
                .isInstanceOf(AligoException.class)
                .hasMessageContaining("템플릿 코드");

        verify(client, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("★ 수신 번호가 없으면 던진다 — 실패로 남아야 「왜 안 왔지」에 답할 수 있다")
    void throwsWithoutPhone() {
        assertThatThrownBy(() -> sender.send(toPhone(null), template("UM_8230")))
                .isInstanceOf(AligoException.class)
                .hasMessageContaining("전화번호");
    }
}
