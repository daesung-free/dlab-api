package com.dlab.domain.lecture.entity;

/**
 * 특강 출석 상태.
 *
 * <p><b>출결 원장 7종과 별개다.</b> 저쪽은 키오스크 태깅 이벤트고 이쪽은 강사가 손으로 체크하는
 * 출석부다. 같은 enum을 쓰면 하원·복귀 같은 값이 특강 출석부에 나타난다.
 */
public enum LectureAttendanceStatus {
    PRESENT,
    ABSENT,
    LATE
}
