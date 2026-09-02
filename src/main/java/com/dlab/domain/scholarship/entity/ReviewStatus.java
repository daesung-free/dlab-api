package com.dlab.domain.scholarship.entity;

/**
 * 검토 대상 상태.
 *
 * <p><b>자동으로 {@link #CANCELED}가 되지 않는다.</b> 판정은 자동이고 확정은 사람이다 —
 * 시트가 <i>"지점 상황이나 개인별 사정에 의해 예외를 두는 경우가 많이 발생한다"</i>고
 * 명시했고, 자동 확정하면 예외인 학생 장학금이 조용히 날아간다.
 */
public enum ReviewStatus {

    /** 걸렸지만 아직 사람이 안 봤다. */
    PENDING,

    /** 취소 확정. */
    CANCELED,

    /** 예외 인정 — 사유가 남아야 한다. */
    EXCEPTED;

    public boolean isDecided() {
        return this != PENDING;
    }
}
