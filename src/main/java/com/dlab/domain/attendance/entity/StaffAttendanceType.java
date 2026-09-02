package com.dlab.domain.attendance.entity;

/**
 * 직원 근태 구분.
 *
 * <p><b>둘뿐이다.</b> 지각·조퇴 판정을 하지 않는다 — 그건 근무시간 마스터가 있어야 하는데
 * 요구에 없다. 지금은 기록만 남기고 판정은 하지 않는다.
 */
public enum StaffAttendanceType {

    IN,
    OUT;

    /** 그날 마지막 기록의 반대. 기록이 없으면 출근이다. */
    public static StaffAttendanceType next(StaffAttendanceType last) {
        return last == IN ? OUT : IN;
    }

    /**
     * 키오스크에 돌려줄 {@code att_gn}.
     *
     * <p>⚠️ <b>키오스크 화면에는 "등원"·"하원"으로 뜬다.</b> DSA 코드에 출퇴근이 없어서
     * 등하원 코드를 그대로 쓴다 — 키오스크를 수정하지 않기로 한 전제 때문이다.
     * 직원 화면 문구를 따로 내려면 키오스크 수정이 필요하다.
     */
    public AttendanceEventType toDsaEvent() {
        return this == IN ? AttendanceEventType.CHECK_IN : AttendanceEventType.CHECK_OUT;
    }
}
