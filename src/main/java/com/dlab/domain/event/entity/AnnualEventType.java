package com.dlab.domain.event.entity;

/**
 * 행사 종류.
 *
 * <p>화면이 색·아이콘을 가르는 용도다. <b>교습일수·급식 계산에는 쓰지 않는다</b> —
 * 그건 {@code holiday} 가 한다.
 */
public enum AnnualEventType {
    /** 학원 행사 — 개원식·수련회·설명회 */
    ACADEMY,
    /** 시험 — 모의고사·평가원·수능 */
    EXAM,
    /** 공휴일에 겹쳐 있는 행사. 쉬는 날 자체는 holiday 에 따로 등록한다 */
    HOLIDAY_EVENT,
    ETC
}
