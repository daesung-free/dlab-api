package com.dlab.domain.notification.service;

import com.dlab.domain.appconfig.repository.NotificationPreferenceRepository;
import com.dlab.domain.notification.entity.NotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 「이 사람에게 이 알림을 보내도 되는가」 판정 — <b>한 군데</b>.
 *
 * <h2>왜 따로 뺐나</h2>
 * 판정이 두 곳에 있었다. 직원 수동 발송 미리보기는 수신거부자를 {@code OPTED_OUT} 으로
 * 빼고 보여주는데 {@link NotificationService#send} 는 <b>설정을 아예 읽지 않아</b>
 * 자동 발송(출결·승인·미등원·설문)이 그대로 나갔다. 지금은 알림톡 템플릿이 심사 중이고
 * 테스트 모드라 드러나지 않지만, <b>심사가 통과하는 날부터 거부한 학부모에게 알림이 나간다.</b>
 *
 * <p>게다가 조건이 둘로 갈리면 「미리보기에선 빠졌는데 실제로는 갔다」가 된다 —
 * 그래서 미리보기와 발송이 <b>같은 메서드</b>를 부른다.
 *
 * <h2>★ 필수 알림(거부 불가)은 설정을 보지 않는다</h2>
 * {@code MANDATORY} 는 앱 수신 설정이 <b>이미 쓰고 있던</b> 목록을 그대로 옮긴 것이다
 * ({@code AppNotificationSettingService} · {@code REQUIRED_NOTIFICATION_CANNOT_BE_DISABLED} ·
 * 마이그레이션 {@code V20260805220000} 주석 <i>"필수 알림은 애플리케이션에서 끄지 못하게
 * 막는다"</i>). 끌 수 없으므로 꺼진 행이 생길 일도 없지만, 과거 데이터나 직접 UPDATE 로
 * 남아 있어도 발송은 나가야 하므로 발송 직전에 한 번 더 막는다.
 *
 * <p>⚠️ <b>이 목록을 우리 판단으로 늘리지 말 것 — 정책이다.</b> 근거가 없는 이벤트는
 * 전부 거부 대상으로 둔다. 특히 승인 요청({@code APPROVAL_*})은 근거가 없어
 * <b>거부 가능</b>으로 두었는데, 거부하면 학부모에게 요청 알림이 가지 않아 10분 뒤
 * 교직원에게 승인권이 이양된다(§3). 그것이 의도된 동작인지는 <b>클라이언트 확인이
 * 필요하다</b> — 확인되면 이 목록에 한 줄 더하는 일이다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOptOutPolicy {

    /**
     * 수신 거부할 수 없는 알림.
     *
     * <p>미등원은 <b>학생이 학원에 오지 않았다는 통지</b>라 학부모가 끌 수 있으면 안 된다.
     * 알림톡으로 격상한 것도 같은 이유이고, SMS 폴백이 폐기(E-5)돼 대체 수단도 없다.
     */
    private static final Set<NotificationEvent> MANDATORY =
            EnumSet.of(NotificationEvent.MISSING_ATTENDANCE);

    private final NotificationPreferenceRepository preferenceRepository;

    /** 앱이 토글을 비활성으로 그려야 하는 유형. 누를 수 있는데 서버가 거절하면 고장으로 보인다. */
    public boolean mandatory(NotificationEvent event) {
        return MANDATORY.contains(event);
    }

    /**
     * 이 계정이 해당 알림을 <b>끄지 않았는가</b>.
     *
     * <p>행이 없으면 수신이다(opt-out) — "켜진 행이 있는가"로 물으면 설정을 한 번도
     * 안 만진 사람에게 알림이 안 간다.
     *
     * @param accountId {@code null} 이면 계정 없는 수신자(입학 대기자·급식업체)다.
     *                  설정을 가질 수 없으므로 수신으로 본다
     */
    public boolean enabled(Long accountId, NotificationEvent event) {
        if (MANDATORY.contains(event)) {
            return true;
        }
        if (accountId == null) {
            return true;
        }
        return !preferenceRepository
                .existsByAccountIdAndEventCodeAndEnabledFalse(accountId, event);
    }

    /** {@link #enabled} 의 반대. 부르는 쪽 문장이 자연스러운 방향을 골라 쓴다. */
    public boolean optedOut(Long accountId, NotificationEvent event) {
        return !enabled(accountId, event);
    }
}
