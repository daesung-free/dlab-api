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
     * 정기일정 미인정 (F-4.1-7).
     *
     * <p>등록 시각과 실제 출입이 30분 이상 어긋난 경우다. 조건값은
     * {@code NOT_RECOGNIZED} 하나뿐이라 규칙에서 조건을 비워두면 전부 걸린다.
     */
    REGULAR_SCHEDULE
}
