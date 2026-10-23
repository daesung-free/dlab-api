package com.dlab.domain.notification.service;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationTemplate;

/**
 * 채널별 실제 발송 구현. 카카오 알림톡·FCM 연동체는 도메인 로직이 안정된 뒤
 * {@code integration} 패키지에서 이 인터페이스를 구현한다. 도메인은 채널이 무엇으로
 * 구현되는지 알지 않는다.
 *
 * <h2>★ 구현체는 채널당 하나다</h2>
 * {@link NotificationService} 가 {@link #channel()} 로 키를 잡아 고른다 — 한 구현체가
 * 두 채널을 받을 수 없다. 알림톡·문자처럼 같은 벤더라도 <b>클래스를 따로</b> 만든다
 * (엔드포인트·성공코드·필수값이 전부 다르다).
 */
public interface NotificationSender {

    NotificationChannel channel();

    /**
     * @param template 이 이력을 만든 템플릿. <b>알림톡은 승인된 {@code kakaoTemplateCode}
     *                 가 필요해서 받는다</b> — 이력({@code log})에는 그 값이 없다.
     *                 ⚠️ 코드 유무 판정은 발송 경로({@code NotificationService})가 이미
     *                 했으므로, 구현체는 없을 때 던지기만 한다(SKIPPED 를 덮지 않는다).
     */
    void send(NotificationLog log, NotificationTemplate template);
}
