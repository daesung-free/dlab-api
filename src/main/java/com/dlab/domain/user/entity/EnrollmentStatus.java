package com.dlab.domain.user.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 재원 상태 5종. 가입 승인 상태({@link AccountStatus})와 별개 축이다.
 *
 * <p><b>퇴원과 제적을 합치지 말 것.</b> 후속처리는 같아도 재등록 심사·환불 산정·통계에서
 * 다르게 취급된다. 한번 뭉뚱그리면 과거 건은 되살릴 수 없다.
 */
public enum EnrollmentStatus {

    /** 재원. */
    ENROLLED,

    /** 휴원 — 복귀를 전제로 한 일시 중단이라 종료가 아니다. */
    ON_LEAVE,

    /** 퇴원(자진). */
    WITHDRAWN,

    /** 제적(강제). */
    EXPELLED,

    /** 수료 — 과정을 정상 종료한 경우. */
    GRADUATED;

    private static final Set<EnrollmentStatus> TERMINAL =
            EnumSet.of(WITHDRAWN, EXPELLED, GRADUATED);

    /**
     * 등록이 끝난 상태인가.
     *
     * <p>후속처리(카드 무효화·배정 해제·계정 비활성)의 기준이다.
     * <b>휴원은 포함되지 않는다</b> — 복귀 예정이라 배정을 풀면 돌아왔을 때 자리가 없다.
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
