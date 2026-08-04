package com.dlab.domain.user.entity;

/**
 * 재적 상태 (요구사항정의서 F-4.1-8 · 3.데이터·업무로직 정의).
 *
 * <p>전이: 재원 → 휴원 → 재원 / 퇴원 / 제적 / 수료.
 *
 * <p><b>가입 승인 상태({@code account.status})와 다른 축이다.</b> 앱 로그인 가능 여부와
 * 학원에 적을 두고 있는지는 별개다 — 퇴원생 계정이 살아 있을 수도 있다.
 */
public enum EnrollmentStatus {

    /** 재원. */
    ENROLLED,

    /** 휴원. 돌아올 수 있으므로 배정·계정을 정리하지 않는다. */
    LEAVE,

    /** 퇴원(본인 의사). */
    WITHDRAWN,

    /** 제적(학원 조치). 퇴원과 사유가 다르므로 통계에서 구분된다. */
    EXPELLED,

    /** 수료. */
    GRADUATED;

    /**
     * 이 상태가 되면 자리·계정을 정리해야 하는가.
     *
     * <p><b>휴원은 포함하지 않는다</b> — 돌아올 학생의 좌석·사물함을 비우면
     * 복귀 때 다시 배정해야 하고, 그 사이 다른 학생이 들어가 자리를 잃는다.
     */
    public boolean requiresCleanup() {
        return this == WITHDRAWN || this == EXPELLED || this == GRADUATED;
    }
}
