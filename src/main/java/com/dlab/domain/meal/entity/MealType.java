package com.dlab.domain.meal.entity;

/**
 * 식사 구분. 하루 2끼(점심·저녁)다.
 *
 * <p>⚠️ <b>DSA 계약은 요청과 응답의 표기가 다르다</b>(CLAUDE.md §7 "고치지 말 것"):
 * <ul>
 *   <li>요청 {@code meal_gb} — {@code L} / {@code D}</li>
 *   <li>응답 {@code meal_gb} — <b>{@code "점심"} / {@code "저녁"} 문자열</b></li>
 * </ul>
 * 키오스크가 응답을 {@code contains("점심")}으로 판정하므로 코드로 통일하면 못 읽는다.
 */
public enum MealType {

    LUNCH("L", "점심"),
    DINNER("D", "저녁");

    private final String requestCode;
    private final String responseLabel;

    MealType(String requestCode, String responseLabel) {
        this.requestCode = requestCode;
        this.responseLabel = responseLabel;
    }

    /** 요청에 실리는 값({@code L}/{@code D}). */
    public String requestCode() {
        return requestCode;
    }

    /** 응답에 실리는 값({@code 점심}/{@code 저녁}). */
    public String responseLabel() {
        return responseLabel;
    }

    /** 알 수 없는 값이면 {@code null}. 호출부가 파라미터 오류로 처리한다. */
    public static MealType fromRequestCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase();
        for (MealType type : values()) {
            if (type.requestCode.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
