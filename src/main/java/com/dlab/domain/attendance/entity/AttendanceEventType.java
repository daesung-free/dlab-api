package com.dlab.domain.attendance.entity;

/**
 * 출결 태깅 이벤트 7종. <b>DSA 원본 코드를 그대로 저장한다</b> — 저장값이 곧 키오스크
 * 응답값이라, 우리 식으로 바꾸면 호환 구획에서 매번 역매핑해야 하고 하나만 틀려도 파싱이 깨진다.
 *
 * <p>요구사항정의서 2시트의 5종(ON_TIME/LATE/ABSENT/OUT/EXCUSED)은 틀렸다 —
 * 하원·복귀가 빠져 있고, 하원 없이는 순공시간 계산이 불가능하다.
 *
 * <p><b>결석(ABSENT)은 여기 없다.</b> "안 찍은 것"이라 태깅 로그에 남을 수 없고,
 * 배치가 일자 단위로 확정하는 파생 상태값이다({@link DailyStatus}).
 */
public enum AttendanceEventType {

    CHECK_IN("S"),
    CHECK_OUT("T"),
    LATE("A"),
    OUTING("D"),
    EXCUSED_OUTING("N"),
    EARLY_LEAVE("C"),
    RETURN("R");

    private final String code;

    AttendanceEventType(String code) {
        this.code = code;
    }

    /** DSA 호환 응답에 실리는 att_gn 값. */
    public String getCode() {
        return code;
    }

    public static AttendanceEventType fromCode(String code) {
        for (AttendanceEventType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 출결 코드: " + code);
    }

    /** 등원으로 인정되는 이벤트 (미등원 판정의 기준). */
    public boolean isArrival() {
        return this == CHECK_IN || this == LATE;
    }
}
