package com.dlab.domain.approval.entity;

/** 승인 주체. */
public enum ApproverType {
    PARENT,
    TEACHER,
    /** 사람 개입 없이 자동 승인 */
    AUTO,

    /**
     * 관리자 대리 처리 (F-4.1-6).
     *
     * <p><b>{@link #TEACHER}로 기록하면 안 된다</b> — 담임이 아닌 사람이 담임으로 남아
     * "누가 승인했나"에 답할 수 없게 된다.
     *
     * <p><b>승인 정책({@code approval_item.approver_type})으로는 고를 수 없다.</b>
     * 라우팅 대상이 아니라 <b>처리 결과</b>다. 대리로 라우팅한다는 건 애초에
     * 승인 절차가 없다는 뜻이라 {@link #AUTO}와 같아진다.
     */
    ADMIN
}
