package com.dlab.domain.survey.entity;

/**
 * 배포 범위.
 *
 * <p>공지(F-4.11-3)와 달리 <b>개별(INDIVIDUAL)이 없다</b> — 한 명에게만 묻는 건 설문이 아니라
 * 질의응답이고, 집계 화면도 의미가 없다.
 */
public enum SurveyScope {

    /** 전 지점. 본사만 낼 수 있다. */
    ALL,

    /** 한 지점. */
    BRANCH,

    /** 한 반. */
    CLASS
}
