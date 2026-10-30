package com.dlab.domain.penalty.entity;

/**
 * 자동부여 트리거 종류.
 *
 * <p><b>{@code PATROL_SLEEP_2}(2회 연속 졸음)를 넣지 말 것</b> —
 * 사감 순찰이 0803에 전면 폐기되면서 이 트리거도 삭제됐다(요구사항 3시트 ⑤).
 * 순공시간 산출식에서도 순찰 기준이 빠졌다.
 */
public enum PenaltyTriggerType {

    /** 출결 태깅 결과(지각·결석·조퇴 등). */
    ATTENDANCE,

    /** 데일리 루틴 결과(미제출·결시 등). */
    DAILY_ROUTINE,

    /**
     * ★ 사유 신청이 <b>승인</b>될 때 (디랩 1029 답변서 4-1).
     *
     * <p>조건값은 {@code AbsenceReasonType} 이름이다 —
     * {@code ABSENCE} · {@code LATE} · {@code EARLY_LEAVE} · {@code OUTING}.
     *
     * <h2>★★ 다른 트리거와 방향이 다르다</h2>
     * 나머지는 <b>학생 행위</b>(태깅·루틴)에 붙는데 이것은 <b>직원의 승인</b>에 붙는다.
     * 시트 원문이 *「직원이 사유 확인 후 승인 시」* 다 — 사유가 인정됐다는 사실 자체가
     * 벌점 사유다(무단보다 가볍게, 그러나 0 은 아니게).
     *
     * <p>⚠️ <b>반려·취소되면 거둬야 한다</b>({@code revokeAuto}) — 승인으로 붙은 것이라
     * 승인이 뒤집히면 근거가 사라진다.
     */
    APPROVAL,

    /**
     * 정기일정 미인정 (F-4.1-7).
     *
     * <p>등록 시각과 실제 출입이 30분 이상 어긋난 경우다. 조건값은
     * {@code NOT_RECOGNIZED} 하나뿐이라 규칙에서 조건을 비워두면 전부 걸린다.
     */
    REGULAR_SCHEDULE
}
