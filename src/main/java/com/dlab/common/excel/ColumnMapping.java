package com.dlab.common.excel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 엑셀 헤더명 ↔ 필드 매핑.
 *
 * <p><b>컬럼 위치가 아니라 헤더명으로 찾는다.</b> 요구사항 F-4.1-2가 "ColumnMapping 기반
 * 유연 매핑(양식 변경 대응)"을 요구한다 — 운영팀이 컬럼 순서를 바꾸거나 중간에 열을
 * 하나 끼워 넣어도 업로드가 깨지면 안 된다.
 *
 * <p><b>별칭을 지원한다.</b> 같은 뜻인데 표기가 다른 경우가 실제로 많다
 * ("학번"/"학생번호", "연락처"/"전화번호"). 하나라도 맞으면 그 컬럼으로 본다.
 *
 * <p>비교 시 <b>공백을 제거하고 대소문자를 무시</b>한다. 엑셀 헤더에 눈에 안 보이는
 * 공백이 섞여 있는 경우가 흔한데, 그것 때문에 "컬럼을 못 찾았습니다"가 나오면
 * 사용자가 원인을 알 수 없다.
 */
public final class ColumnMapping {

    private final Map<String, Column> columns = new LinkedHashMap<>();

    private ColumnMapping() {
    }

    public static ColumnMapping builder() {
        return new ColumnMapping();
    }

    /**
     * @param field   내부 필드명. {@link ExcelRow}에서 이 이름으로 꺼낸다
     * @param headers 엑셀 헤더 후보. 첫 번째가 대표 표기(Export 시 사용)
     */
    public ColumnMapping column(String field, boolean required, String... headers) {
        columns.put(field, new Column(field, required, List.of(headers)));
        return this;
    }

    public ColumnMapping required(String field, String... headers) {
        return column(field, true, headers);
    }

    public ColumnMapping optional(String field, String... headers) {
        return column(field, false, headers);
    }

    /** 헤더 행에서 각 필드가 몇 번째 열인지 찾는다. 못 찾으면 -1. */
    public Map<String, Integer> resolve(List<String> headerRow) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (Column column : columns.values()) {
            resolved.put(column.field(), indexOf(headerRow, column));
        }
        return resolved;
    }

    /** 필수인데 헤더에 없는 컬럼. 파일 자체를 거부해야 하는 경우다. */
    public List<String> missingRequired(List<String> headerRow) {
        Map<String, Integer> resolved = resolve(headerRow);
        return columns.values().stream()
                .filter(Column::required)
                .filter(c -> resolved.get(c.field()) < 0)
                .map(c -> c.headers().get(0))
                .toList();
    }

    /** Export용 헤더. 대표 표기를 순서대로. */
    public List<String> exportHeaders() {
        return columns.values().stream().map(c -> c.headers().get(0)).toList();
    }

    public List<String> fields() {
        return List.copyOf(columns.keySet());
    }

    public boolean isRequired(String field) {
        Column column = columns.get(field);
        return column != null && column.required();
    }

    private int indexOf(List<String> headerRow, Column column) {
        for (int i = 0; i < headerRow.size(); i++) {
            String actual = normalize(headerRow.get(i));
            for (String candidate : column.headers()) {
                if (normalize(candidate).equals(actual)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private record Column(String field, boolean required, List<String> headers) {
    }
}
