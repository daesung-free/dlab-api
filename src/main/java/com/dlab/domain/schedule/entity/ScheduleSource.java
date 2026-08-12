package com.dlab.domain.schedule.entity;

/** 정기일정 등록 주체 (0803 2트랙). */
public enum ScheduleSource {

    /** 학생 앱 등록 — 승인 라우팅을 탄다. */
    STUDENT,

    /**
     * 담임이 웹에서 대신 등록 — 자동 승인.
     *
     * <p>승인자가 곧 등록자라 자기가 넣고 자기가 승인하는 절차를 만들 이유가 없다.
     */
    ADMIN
}
