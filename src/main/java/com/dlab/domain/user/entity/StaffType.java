package com.dlab.domain.user.entity;

/** 선생님 구분. 공지 작성 권한이 이 값에 따라 갈린다. */
public enum StaffType {
    /** 담당선생님(사감) — 반공지 작성, 방화벽 에스컬레이션 승인 */
    HOMEROOM,
    /** 행정선생님 — 전체공지 작성 */
    ADMIN
}
