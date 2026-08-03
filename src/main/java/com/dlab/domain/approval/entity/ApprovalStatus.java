package com.dlab.domain.approval.entity;

/**
 * 승인 상태. 이 4종뿐이다.
 *
 * <p>"타임아웃 후 승인됨"은 상태가 아니라 결과 속성이라 {@link ResolutionCase}가 담당한다.
 * 여기에 TIMEOUT_ESCALATED 같은 값을 섞으면 "에스컬레이션됐지만 거절"을 표현할 수 없다.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELED
}
