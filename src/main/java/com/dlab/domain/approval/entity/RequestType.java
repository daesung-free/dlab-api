package com.dlab.domain.approval.entity;

/**
 * 승인 대상 유형. 사유신청·정기일정·방화벽이 하나의 라우팅 엔진을 공유한다
 * — 각자 승인 컬럼을 갖게 하면 같은 로직을 세 번 짜게 된다.
 */
public enum RequestType {
    FIREWALL_UNLOCK,
    ABSENCE_REASON,
    REGULAR_SCHEDULE
}
