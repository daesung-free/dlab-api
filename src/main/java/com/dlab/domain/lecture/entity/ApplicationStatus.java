package com.dlab.domain.lecture.entity;

/**
 * 특강 신청 상태.
 *
 * <p><b>대기자를 별도 테이블로 만들지 않는다</b> — 정원이 차면 같은 행이 {@link #WAITLISTED}로
 * 들어가고 자리가 나면 {@link #APPLIED}로 승격된다. 테이블을 나누면 전환 때 행을 옮겨야 하고
 * 그 과정에서 <b>신청 시각(= 대기 순번의 근거)</b>을 잃기 쉽다.
 */
public enum ApplicationStatus {
    APPLIED,
    WAITLISTED,
    CANCELED;

    public boolean isActive() {
        return this != CANCELED;
    }
}
