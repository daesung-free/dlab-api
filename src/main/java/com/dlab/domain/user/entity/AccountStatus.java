package com.dlab.domain.user.entity;

/**
 * 계정 상태.
 * 학생만 PENDING을 거치고(관리자 승인 전 앱 접근 완전 차단), 학부모는 가입 즉시 ACTIVE다.
 * 중간 상태는 두지 않는다.
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    WITHDRAWN,

    /**
     * 가입 반려.
     *
     * <p>★ <b>PENDING 과 가른다.</b> 합쳐 두면 관리자 대기 목록에 반려한 건이 계속 남고,
     * 학생은 「아직 심사 중」과 「반려됐다」를 구분할 수 없다.
     *
     * <p>사유는 {@code signup_rejection} 에 <b>행으로 쌓인다</b> — 재신청이 여러 번
     * 가능하므로 마지막 사유만 남기면 몇 번 반려됐는지에 답할 수 없다.
     *
     * <p>⚠️ <b>되살리는 경로는 재신청뿐이다.</b> 반려를 승인으로 뒤집지 않는다 —
     * 반려 사유가 고쳐졌는지는 학생이 다시 낸 내용으로만 알 수 있다.
     */
    REJECTED
}
