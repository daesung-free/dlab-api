package com.dlab.domain.survey.entity;

/**
 * 설문 용도.
 *
 * <p>구조는 같고 화면 분류만 갈린다 — 가채점도 "과목별 자기 점수"라 숫자 문항 여러 개다.
 * 따로 만들면 배포·마감·집계를 두 번 짜게 된다.
 */
public enum SurveyType {

    /** 일반 설문. */
    GENERAL,

    /** 가채점 — 시험 직후 자기 점수 입력(F-4.6). */
    GRADE_INPUT
}
