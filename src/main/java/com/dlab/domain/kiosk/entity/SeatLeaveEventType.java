package com.dlab.domain.kiosk.entity;

/** 좌석 이탈·복귀 구분. */
public enum SeatLeaveEventType {

    LEAVE,

    /** 학생이 실제로 돌아왔다. */
    RETURN,

    /**
     * 키오스크가 매일 00:30에 미복귀 건을 일괄 마감한 것 — <b>복귀가 아니다</b>.
     *
     * <p><b>★ 미복귀 판정에서 이걸 복귀로 세면 안 된다.</b> 세는 순간 "23시 이탈 →
     * 00:30 복귀"가 되어 <b>정작 잡아야 할 장시간 미복귀가 자동으로 닫힌다.</b>
     *
     * <p>{@code RETURN} + 플래그로 두지 않은 이유도 같다 — 값이 다르면 어느 코드에서든
     * 실수로 섞일 수 없지만, 플래그는 조건 하나만 빠뜨려도 조용히 복귀로 집계된다.
     */
    AUTO_CLOSE;

    /** 이탈을 닫는 사건인가(실제 복귀 + 자동 마감). 좌석 상태 재구성이 쓴다. */
    public boolean closesLeave() {
        return this == RETURN || this == AUTO_CLOSE;
    }

    /** <b>실제</b> 복귀인가. 미복귀 판정·통계는 이것만 봐야 한다. */
    public boolean isRealReturn() {
        return this == RETURN;
    }
}
