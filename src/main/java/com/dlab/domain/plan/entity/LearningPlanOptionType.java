package com.dlab.domain.plan.entity;

/**
 * 학습계획 드롭다운 축.
 *
 * <p>통계가 <b>과목별·형태별</b> 둘로 나오므로 축이 둘이다. 값 자체는 코드가 아니라
 * {@code learning_plan_option} 행이다 — 탐구1/2 분리와 과목 커스터마이즈가 요구사항이라
 * 지점·연도마다 달라진다.
 */
public enum LearningPlanOptionType {
    /** 국어·수학·영어·탐구1·탐구2 … */
    SUBJECT,
    /** 수업·인강·자습 */
    STUDY_TYPE
}
