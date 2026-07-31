package com.dlab.domain.notification.entity;

public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    /** 템플릿 문구 미확정 등으로 실제 발송하지 않음 */
    SKIPPED
}
