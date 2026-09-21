package com.dlab.domain.admission.entity;

/**
 * 상담 진행 상태 6종 (2026-09-18 답변서).
 *
 * <h2>★ 재적 상태와 다른 축이다</h2>
 * {@code EnrollmentStatus} 는 <b>학생이 지금 어떤 상태인가</b>(재원·휴원·퇴원…)이고,
 * 이건 <b>신청 건이 어디까지 처리됐나</b>다. 섞으면 아직 학생도 아닌 신청 건이 재원생
 * 통계에 들어간다.
 *
 * <h2>두 축이 만나는 지점은 {@link #CONFIRMED} 하나뿐이다</h2>
 * 거기서 학생을 만들고(학번 채번), 그 뒤로는 재적 상태가 이어받는다.
 */
public enum ConsultStatus {
    /** 통화필요 — 접수 직후 기본값 */
    CALL_NEEDED,
    /** 상담취소 */
    CANCELED,
    /** 상담완료 */
    CONSULTED,
    /** 입학보류 */
    ON_HOLD,
    /** 입학확정 — <b>여기서만 학생으로 전환한다</b> */
    CONFIRMED,
    /** 미등록 */
    NOT_REGISTERED;

    public String displayName() {
        return switch (this) {
            case CALL_NEEDED -> "통화필요";
            case CANCELED -> "상담취소";
            case CONSULTED -> "상담완료";
            case ON_HOLD -> "입학보류";
            case CONFIRMED -> "입학확정";
            case NOT_REGISTERED -> "미등록";
        };
    }
}
