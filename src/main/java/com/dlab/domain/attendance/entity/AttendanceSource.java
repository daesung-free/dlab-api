package com.dlab.domain.attendance.entity;

/** 출결 기록의 입력 경로. 키오스크 카드 태깅이 기본이다(CLAUDE.md §3). */
public enum AttendanceSource {
    KIOSK,
    APP,
    /** 관리자 수기 등록 */
    MANUAL
}
