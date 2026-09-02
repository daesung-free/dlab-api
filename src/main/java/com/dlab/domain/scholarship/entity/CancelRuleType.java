package com.dlab.domain.scholarship.entity;

/**
 * 장학 취소 요건 (0820 규정).
 *
 * <p><b>임계값은 여기 없다.</b> 시트가 <i>"지점별, 연도별 상이 할수 있음"</i>이라고 해서
 * {@code scholarship_cancel_rule} 행이 들고 있다 — 값이 바뀔 때 코드를 고치지 않는다.
 */
public enum CancelRuleType {

    /**
     * 벌점 누적 40점 이상. <b>제적 기준과 같은 값</b>이라 상벌점 쪽과
     * 한 군데에서만 관리한다 — 두 벌 두면 한쪽만 바뀐다.
     */
    PENALTY_POINT,

    /**
     * 6월 또는 9월 평가원 <b>3과목 등급합</b>이 임계값을 넘음.
     *
     * <p>⚠️ 규정에 <b>"3과목"이라고만 적혀 있고 어느 과목인지가 없다.</b>
     * 국·수·영으로 추정해 규칙에 넣어 뒀으나 <b>확인 전까지 켜지 않는다.</b>
     */
    EXAM_GRADE_SUM,

    /**
     * 더프리미엄 모의고사 2회 미응시.
     *
     * <p>⚠️ <b>지금 판정할 수단이 없다.</b> 더프리미엄 응시 이력이 우리 DB에 없어서
     * E-2(API 명세) 수령 전까지는 규칙을 켜도 아무것도 못 찾는다.
     */
    MOCK_EXAM_ABSENCE;

    /** 지금 우리 데이터만으로 판정할 수 있는가. */
    public boolean isJudgeable() {
        return this != MOCK_EXAM_ABSENCE;
    }
}
