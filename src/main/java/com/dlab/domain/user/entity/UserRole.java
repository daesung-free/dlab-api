package com.dlab.domain.user.entity;

/** 계정 권한. JWT role 체크의 기준이 된다(CLAUDE.md §7). */
public enum UserRole {
    STUDENT,
    PARENT,
    /** 담당선생님(사감). 반 담임이자 방화벽 에스컬레이션 승인자. */
    STAFF_HOMEROOM,
    /** 행정선생님. 전체공지 작성 권한을 가진다. */
    STAFF_ADMIN,
    /** 전 지점 조회 가능. */
    SUPER_ADMIN;

    public boolean isStaff() {
        return this == STAFF_HOMEROOM || this == STAFF_ADMIN || this == SUPER_ADMIN;
    }
}
