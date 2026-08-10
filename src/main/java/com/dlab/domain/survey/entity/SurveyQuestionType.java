package com.dlab.domain.survey.entity;

/**
 * 문항 유형.
 *
 * <p>유형마다 저장 칸이 다르다 — 선택형은 선택지 id, 주관식은 문자열, 숫자형은 수치다.
 * 한 칸에 몰아넣고 문자열로 저장하면 <b>평균·선택지별 응답 수를 SQL로 못 낸다</b>.
 */
public enum SurveyQuestionType {

    /** 단일 선택. */
    SINGLE_CHOICE,

    /** 복수 선택. 고른 개수만큼 답 행이 생긴다. */
    MULTI_CHOICE,

    /** 주관식. */
    TEXT,

    /** 숫자. 가채점 점수가 이걸 쓴다. */
    NUMBER;

    public boolean isChoice() {
        return this == SINGLE_CHOICE || this == MULTI_CHOICE;
    }
}
