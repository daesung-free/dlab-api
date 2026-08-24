package com.dlab.domain.grade.entity;

/**
 * 시험 회차 구분.
 *
 * <p><b>"6월 학력평가"와 "6월 평가원"을 나누지 않는다.</b> 어느 쪽인지는 학년
 * ({@code grade_type})이 이미 가르고, 표시 문구는 {@link ExamMaster#getExamName()}에
 * 신상기록부 원문 그대로 들어 있다. 코드를 나누면 학년별 통계에서 같은 6월 시험이
 * 두 축으로 쪼개진다.
 */
public enum ExamCode {

    /** 6월. 예비고2·예비고3은 학력평가, N수·현고3은 평가원. */
    JUNE,

    /** 9월. */
    SEPT,

    /** 10월 학력평가. 재학생만 본다 — N수·현고3 양식에는 없다. */
    OCT,

    /** 수능. N수·현고3만 해당하며 <b>전년도</b> 성적이다. */
    CSAT
}
