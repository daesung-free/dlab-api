package com.dlab.domain.admission.entity;

/**
 * 지원 결과.
 *
 * <h2>★ 불합격과 등록포기를 합치지 않는다</h2>
 * {@code boolean} 하나(등록확정 여부)로 두면 <b>"불합격"과 "합격했는데 등록하지 않음"이
 * 구분되지 않는다.</b> 실적 집계에서 이 둘은 완전히 다른 숫자다 — 합격률은 같이 오르고
 * 등록률만 갈린다.
 *
 * <h2>발표 전({@code PENDING})도 따로 둔다</h2>
 * 불합격으로 세면 발표 전인 지원까지 실패로 집계된다.
 */
public enum AdmissionResultStatus {
    /** 아직 발표 전 */
    PENDING,
    PASSED,
    FAILED,
    /** 합격했으나 등록하지 않음 */
    GAVE_UP;

    /** 합격 이후(등록포기 포함)인가 — 합격률 집계용 */
    public boolean isPass() {
        return this == PASSED || this == GAVE_UP;
    }
}
