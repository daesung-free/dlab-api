package com.dlab.common.excel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 엑셀 한 행. 값은 전부 문자열로 읽어두고 여기서 타입 변환한다.
 *
 * <p><b>변환 실패를 예외로 던지지 않고 {@link #errors}에 모은다.</b>
 * 요구사항이 "오류행 표시 후 정상행만 반영"이라, 첫 오류에서 멈추면 사용자가
 * 파일을 고쳐 올릴 때마다 오류를 하나씩만 발견하게 된다.
 *
 * @param rowNumber 엑셀 기준 행 번호(1-based, 헤더 포함). 오류 메시지에 그대로 쓴다
 */
public record ExcelRow(int rowNumber, Map<String, String> values, java.util.List<RowError> errors) {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,          // 2026-03-02
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.BASIC_ISO_DATE           // 20260302
    };

    public static ExcelRow of(int rowNumber, Map<String, String> values) {
        return new ExcelRow(rowNumber, values, new java.util.ArrayList<>());
    }

    public String text(String field) {
        String value = values.get(field);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 값이 없으면 오류로 기록하고 null을 돌려준다. */
    public String requiredText(String field, String label) {
        String value = text(field);
        if (value == null) {
            addError(field, label + "은(는) 필수입니다.");
        }
        return value;
    }

    public Integer number(String field, String label) {
        String value = text(field);
        if (value == null) {
            return null;
        }
        try {
            // 엑셀이 숫자를 "2026.0"으로 내려주는 경우가 있다
            return (int) Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            addError(field, label + "은(는) 숫자여야 합니다. (입력값: " + value + ")");
            return null;
        }
    }

    /**
     * 날짜. 표기가 여러 가지로 들어온다 — 운영팀이 쓰는 엑셀마다 서식이 다르다.
     * 하나라도 맞으면 통과시킨다.
     */
    public LocalDate date(String field, String label) {
        String value = text(field);
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // 다음 형식 시도
            }
        }
        addError(field, label + " 날짜 형식을 인식할 수 없습니다. (입력값: " + value + ")");
        return null;
    }

    public void addError(String field, String message) {
        errors.add(new RowError(rowNumber, field, message));
    }

    public boolean hasError() {
        return !errors.isEmpty();
    }

    /** 모든 값이 비어 있는 행. 엑셀 끝의 빈 줄을 오류로 잡지 않으려고 걸러낸다. */
    public boolean isEmpty() {
        return values.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
