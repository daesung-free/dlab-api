package com.dlab.domain.user.entity;

/**
 * 계정 상태. 학생만 PENDING을 거치고(관리자 승인 전 앱 접근 완전 차단),
 * 학부모는 가입 즉시 ACTIVE다(CLAUDE.md §3). 중간 상태는 두지 않는다.
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    SUSPENDED
}
