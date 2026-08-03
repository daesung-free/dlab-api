package com.dlab.domain.notification.entity;

/** 놓치면 안 되는 알림은 카카오 알림톡, 일반 알림은 FCM Push. */
public enum NotificationChannel {
    /** 카카오 알림톡. 템플릿 사전심사(E-5)가 필요하다. */
    KAKAO_ALIMTALK,
    FCM_PUSH
}
