package com.dlab.domain.attendance.entity;

/**
 * 일자 단위 최종 출결 상태. 배치가 태깅 원장을 보고 확정하는 파생값이다.
 * <b>ABSENT는 오직 여기에만 존재한다</b> — 태깅 이벤트가 아니기 때문.
 */
public enum DailyStatus {
    PRESENT,
    LATE,
    ABSENT,
    EARLY_LEAVE
}
