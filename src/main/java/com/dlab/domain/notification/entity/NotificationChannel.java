package com.dlab.domain.notification.entity;

/**
 * 발송 채널.
 * 놓치면 안 되는 알림(미등원, 방화벽 승인 등)은 카카오 알림톡, 일반 알림은 FCM Push다(CLAUDE.md §3).
 */
public enum NotificationChannel {
    /** 카카오 알림톡. 템플릿 사전심사가 필요하다. */
    KAKAO_ALIMTALK,
    FCM_PUSH
}
