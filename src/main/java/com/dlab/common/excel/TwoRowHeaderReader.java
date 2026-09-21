package com.dlab.common.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 헤더가 두 줄인 엑셀을 읽는다.
 *
 * <h2>왜 {@link ColumnMapping} 으로 안 되나</h2>
 * 그쪽은 <b>헤더 한 줄</b>을 전제한다. 그런데 모의고사 성적 파일은 위 줄이 영역
 * (국어·수학·영어…), 아래 줄이 항목(표준점수·백분위·등급…)이고 <b>항목명이 심하게 겹친다</b> —
 * 한 파일에 "원점수" 11번, "표준점수" 10번이 나온다. 아래 줄만 보면 어느 과목 점수인지
 * 알 수 없다.
 *
 * <p>그래서 <b>{@code "국어 > 표준점수"} 2단 키</b>로 읽는다. 열 순서가 바뀌거나 과목이
 * 추가돼도 견딘다 — 위치로 읽으면 학년별로 구성이 다른 이 파일에서 매번 깨진다.
 *
 * <h2>병합 셀</h2>
 * 위 줄은 과목마다 한 번만 쓰이고 나머지는 비어 있다(병합). <b>직전 값을 이어서 채운다</b> —
 * 안 그러면 각 과목의 첫 열만 키가 생기고 나머지가 전부 같은 이름으로 뭉친다.
 */
@Component
public class TwoRowHeaderReader {

    /**
     * @param blockRowIndex 위 줄(영역) 인덱스. 0부터 센다
     * @param labelRowIndex 아래 줄(항목) 인덱스
     * @return 헤더 키 목록과 행들. 행의 값은 <b>전부 문자열</b>이다
     *         ({@link ExcelReader} 와 같은 이유 — 셀 서식이 파일마다 다르다)
     */
    public Result read(InputStream input, int blockRowIndex, int labelRowIndex) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row blockRow = sheet.getRow(blockRowIndex);
            Row labelRow = sheet.getRow(labelRowIndex);
            if (blockRow == null || labelRow == null) {
                throw new IllegalArgumentException(
                        "헤더 행을 찾을 수 없습니다: %d·%d 행".formatted(blockRowIndex + 1, labelRowIndex + 1));
            }

            List<String> keys = headerKeys(blockRow, labelRow);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = labelRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean empty = true;
                for (int c = 0; c < keys.size(); c++) {
                    String value = text(row.getCell(c));
                    if (!value.isEmpty()) {
                        empty = false;
                    }
                    // 같은 키가 두 번 나오면 앞을 살린다 — 뒤쪽은 대개 빈 병합 칸이다
                    values.putIfAbsent(keys.get(c), value);
                }
                if (!empty) {
                    // 엑셀에서 보이는 행 번호(1-based). 오류를 알릴 때 사용자가 그 행을 찾을 수 있어야 한다
                    rows.add(withRowNumber(values, i + 1));
                }
            }
            return new Result(keys, rows);
        } catch (IOException e) {
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다.", e);
        }
    }

    /** 행 번호를 값에 얹는다. 별도 필드로 두면 호출부마다 짝지어 들고 다녀야 한다. */
    private Map<String, String> withRowNumber(Map<String, String> values, int rowNumber) {
        values.put(ROW_NUMBER, String.valueOf(rowNumber));
        return values;
    }

    /** 행 번호 키. 엑셀 기준 1부터다. */
    public static final String ROW_NUMBER = "__row";

    private List<String> headerKeys(Row blockRow, Row labelRow) {
        List<String> keys = new ArrayList<>();
        String block = "";
        int last = Math.max(blockRow.getLastCellNum(), labelRow.getLastCellNum());
        for (int c = 0; c < last; c++) {
            String blockText = text(blockRow.getCell(c));
            if (!blockText.isEmpty()) {
                block = blockText;      // 병합으로 비어 있는 칸은 직전 영역에 속한다
            }
            String label = text(labelRow.getCell(c));
            keys.add(key(block, label));
        }
        return keys;
    }

    /** {@code "국어 > 표준점수"}. 영역이 없으면 항목만 쓴다(학교코드·이름 같은 앞쪽 열). */
    public static String key(String block, String label) {
        String b = normalize(block);
        String l = normalize(label);
        return b.isEmpty() || b.equals(l) ? l : b + " > " + l;
    }

    /** 줄바꿈·공백을 없앤다 — 같은 항목이 {@code "원점수\n(공통)"} 처럼 들어온다. */
    /**
     * 공백과 엑셀 줄바꿈 이스케이프를 지운다.
     *
     * <p>★ <b>{@code _x000D_} 가 글자 그대로 들어온다.</b> 셀 안의 줄바꿈(CR)을 엑셀이 이렇게
     * 저장하는데, 연구소 파일의 지망대학 헤더가 {@code "지원자 중 석차_x000D_"} 처럼 되어 있다.
     * 지우지 않으면 {@code "지원자중석차"} 로 찾을 수 없어 그 열이 통째로 빈다.
     */
    private static String normalize(String value) {
        return value == null ? "" : value.replace("_x000D_", "").replaceAll("\\s+", "");
    }

    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> numeric(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formula(cell);
            default -> "";
        };
    }

    /** 숫자 셀의 {@code .0} 꼬리를 없앤다. 학번·반·번호가 정수로 읽혀야 한다. */
    private String numeric(Cell cell) {
        double d = cell.getNumericCellValue();
        return d == Math.floor(d) && !Double.isInfinite(d)
                ? String.valueOf((long) d) : String.valueOf(d);
    }

    private String formula(Cell cell) {
        try {
            return numeric(cell);
        } catch (IllegalStateException e) {
            return cell.getStringCellValue().trim();
        }
    }

    /**
     * @param headerKeys 만들어진 2단 키. 매핑이 안 맞을 때 무엇이 있었는지 보여주는 데 쓴다
     */
    public record Result(List<String> headerKeys, List<Map<String, String>> rows) {
    }
}
