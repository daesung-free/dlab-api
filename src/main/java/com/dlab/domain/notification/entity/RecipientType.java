package com.dlab.domain.notification.entity;

/**
 * 알림 수신자 (앱 요구사항 A-C3 매핑표의 수신자 축).
 *
 * <p><b>{@link #ROUTED}가 따로 있는 이유</b> — 승인 요청은 수신자가 고정이 아니라
 * {@code approval_item.approver_type}에 따라 실행 시점에 갈린다. 여기 {@code PARENT}로
 * 박아두면 승인 주체 매트릭스(I-12)가 바뀔 때 템플릿까지 같이 고쳐야 한다.
 */
public enum RecipientType {
    STUDENT,
    PARENT,
    BOTH,
    /** 승인 주체 설정에 따라 실행 시점에 결정된다. */
    ROUTED
}
