package com.dlab.domain.notification.entity;

/**
 * 알림 이벤트 코드. DB의 notification_template.event_code 와 1:1로 대응한다.
 * 채널 매핑과 문구는 DB 템플릿이 진실의 원천이고, 여기서는 종류만 정의한다.
 */
public enum NotificationEvent {
    /** 등원 기준시각까지 태깅 기록이 없는 무단 미등원 */
    MISSING_ATTENDANCE,
    /** 승인 요청 접수 — 학부모와 담당선생님에게 동시 발송 */
    APPROVAL_REQUEST_CREATED,
    /** 타임아웃 전 학부모 승인 */
    APPROVAL_APPROVED_BY_PARENT,
    /** 타임아웃 경과 후 담당선생님 승인 — "시간이 지나 담임이 승인" */
    APPROVAL_APPROVED_AFTER_TIMEOUT,
    /** 타임아웃 전 담당선생님이 먼저 승인 — "시간이 남았지만 담임이 먼저 승인" (위와 다른 문구) */
    APPROVAL_APPROVED_BEFORE_TIMEOUT,
    /** 학부모 무응답 → 1회 자동 재승인 요청 (0803 답변서) */
    APPROVAL_REMINDER,
    /** 재요청 후에도 무응답 → 직원에게 승인권 이양 */
    APPROVAL_HANDED_OVER,
    /** 우선 승인자가 직원이라 직원이 승인함 — 학부모에겐 통지만 나간다 */
    APPROVAL_APPROVED_BY_STAFF_PRIMARY,
    APPROVAL_REJECTED,
    /** 학생이 상담을 예약함 — 담임에게 (F-4.11-4 "선택한 일정에 알림 연동") */
    CONSULT_RESERVED,
    /** 상담 예약 취소 — 담임이 빈 자리를 알아야 그 시간을 다시 쓴다 */
    CONSULT_CANCELED
}
