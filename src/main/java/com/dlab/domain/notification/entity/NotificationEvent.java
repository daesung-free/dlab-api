package com.dlab.domain.notification.entity;

/**
 * 알림 이벤트 코드. DB의 notification_template.event_code 와 1:1로 대응한다.
 * 채널 매핑과 문구는 DB 템플릿이 진실의 원천이고, 여기서는 이벤트 종류만 정의한다.
 */
public enum NotificationEvent {
    /** 등원 기준시각까지 태깅 기록이 없는 무단 미등원 */
    MISSING_ATTENDANCE,
    /** 방화벽 해제 신청 접수 — 학부모와 담당선생님에게 동시 발송 */
    FIREWALL_REQUEST_CREATED,
    /** 타임아웃 전 학부모 승인 */
    FIREWALL_APPROVED_BY_PARENT,
    /** 타임아웃 경과 후 담당선생님 승인 — "시간이 지나 담임이 승인" */
    FIREWALL_APPROVED_AFTER_TIMEOUT,
    /** 타임아웃 전 담당선생님이 먼저 승인 — "시간이 남았지만 담임이 먼저 승인" (위와 다른 문구) */
    FIREWALL_APPROVED_BEFORE_TIMEOUT,
    FIREWALL_REJECTED
}
