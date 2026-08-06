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

    /** 이 상태에서 실제 알림톡을 보낼 수 있는가. */
    public boolean canSend() {
        return this == NOT_REQUIRED || this == APPROVED;
    }
}
