package com.dlab.domain.admission.entity;

/**
 * 입력 주체.
 *
 * <p>0826 회신이 성적 입력을 <i>"처음 입력시 학생, 이후 수정시에는 직원을 통해서"</i> 로
 * 확정했고 지원대학도 같은 모양이다. <b>누가 넣은 값인지 남지 않으면 직원이 확인한 값과
 * 학생이 적어낸 값이 섞인다</b> — 실적으로 내보낼 때 그 둘은 신뢰도가 다르다.
 */
public enum AdmissionSource {
    STUDENT,
    STAFF
}
