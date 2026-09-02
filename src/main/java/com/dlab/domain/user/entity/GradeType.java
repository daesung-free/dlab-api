package com.dlab.domain.user.entity;

/**
 * 학년 구분. 기획서의 "성인"은 n수생을 의미한다.
 *
 * <p><b>{@link #STAFF}는 학년이 아니다.</b> 직원이 키오스크로 출퇴근을 찍으려면 카드·학번과
 * 키오스크 동기화가 학생과 똑같이 필요해서 같은 등록 테이블을 쓰는데, 그 구분을 이 값으로
 * 한다. 불린 컬럼을 따로 두면 두 값이 어긋난 행이 생겼을 때 어느 쪽이 진실인지 판정할 수 없다.
 */
public enum GradeType {
    HIGH2,
    HIGH3,
    N_SU,

    /**
     * 직원. 학생 조회·집계·배치에서 전부 빠진다.
     *
     * <p>키오스크 동기화만 예외다 — 카드를 인식해야 출퇴근을 찍는다.
     */
    STAFF;

    public boolean isStaff() {
        return this == STAFF;
    }
}
