package com.dlab.domain.period.entity;

/**
 * 교시 유형.
 *
 * <p><b>{@link #BREAK}·{@link #MEAL}은 학습시간이 아니다</b> — 다만 출결 판정에서
 * "학습시간 외"({@code code 122})를 가르는 기준은 개별 교시 유형이 아니라
 * <b>그날 첫 교시 시작 ~ 마지막 교시 종료</b>의 바깥이다. 점심시간에 태깅했다고
 * 거부하면 외출·복귀를 못 찍는다.
 */
public enum PeriodType {
    CLASS,
    SELF_STUDY,
    MEAL,
    BREAK,
    ETC
}
