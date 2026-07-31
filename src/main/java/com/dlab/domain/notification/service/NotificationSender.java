package com.dlab.domain.notification.service;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationLog;

/**
 * 채널별 실제 발송 구현. 카카오 알림톡·FCM 연동체는
 * 도메인 로직이 안정된 뒤 {@code integration} 패키지에서 이 인터페이스를 구현한다(CLAUDE.md §6-2).
 * 도메인은 채널이 무엇으로 구현되는지 알지 않는다.
 */
public interface NotificationSender {

    NotificationChannel channel();

    void send(NotificationLog log);
}
