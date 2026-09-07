package com.dlab.domain.notification.entity;

/**
 * 카카오 알림톡 사전심사 상태 (E-5).
 *
 * <p><b>{@code contentConfirmed}(운영팀 문구 확정)와 다른 축이다.</b> 문구가 확정돼도
 * 카카오 심사를 통과하지 못하면 알림톡은 못 나간다. 한 값으로 합치면
 * "문구는 정해졌는데 심사 대기 중"을 표현할 수 없고, <b>심사 리드타임이 길어
 * 실제로 그 상태에 한참 머문다.</b>
 */
public enum ReviewStatus {
    /** 심사 대상이 아님 (FCM 등). */
    NOT_REQUIRED,
    /** 작성 중 — 아직 제출 안 함. */
    DRAFT,
    /** 카카오에 제출, 결과 대기. */
    SUBMITTED,
    APPROVED,
    /** 반려. 사유를 보고 문구를 고쳐 재제출한다. */
    REJECTED;

    /**
     * 이 상태에서 보낼 수 있는가.
     *
     * <p>⚠️ <b>채널을 모르는 판정이다.</b> {@code NOT_REQUIRED}는 심사가 없는 채널(FCM)을
     * 위한 값인데, 알림톡이 실수로 그 상태면 여기서는 통과한다 — 채널까지 보는 판정은
     * {@link NotificationTemplate#isSendable()}에 있다. 이 메서드만 보고 판단하지 말 것.
     */
    public boolean canSend() {
        return this == NOT_REQUIRED || this == APPROVED;
    }
}
