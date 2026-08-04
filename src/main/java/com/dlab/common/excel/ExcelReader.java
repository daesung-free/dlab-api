package com.dlab.common.excel;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 엑셀 → {@link ExcelRow} 목록.
 *
 * <p>값을 <b>전부 문자열로</b> 읽는다. 셀 서식이 숫자인지 텍스트인지는 파일마다 다른데
 * (학번이 숫자 서식이면 "20260001", 텍스트면 "2026-0001"), 읽는 쪽에서 타입을 가정하면
 * 그때마다 깨진다. 변환은 {@link ExcelRow}에서 필드 의미에 맞춰 한다.
 */
@Component
public class ExcelReader {

    private static final DateTimeFormatter CELL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 방어 한도. 이보다 큰 파일은 업로드 실수로 본다. */
    private static final int MAX_ROWS = 10_000;

    /**
     * @param headerRowIndex 헤더 행의 0-based 인덱스. 보통 0이지만 안내문이 위에 있는 양식도 있다
     */
    public ExcelParseResult read(InputStream input, ColumnMapping mapping, int headerRowIndex) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(headerRowIndex);
            if (header == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "헤더 행을 찾을 수 없습니다.");
            }

            List<String> headerRow = readHeader(header);
            List<String> missing = mapping.missingRequired(headerRow);
            if (!missing.isEmpty()) {
                // 필수 컬럼이 없으면 행 검증이 무의미하다 — 파일 자체를 거부한다.
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "필수 컬럼이 없습니다: " + String.join(", ", missing));
            }

            Map<String, Integer> columnIndex = mapping.resolve(headerRow);
            List<ExcelRow> rows = new ArrayList<>();

            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "한 번에 처리할 수 있는 행 수를 초과했습니다. (최대 " + MAX_ROWS + "행)");
                }
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : columnIndex.entrySet()) {
                    int index = entry.getValue();
                    values.put(entry.getKey(), index < 0 ? null : cellText(row.getCell(index)));
                }
                // 엑셀 끝에 서식만 남은 빈 줄이 흔하다. 오류로 잡으면 사용자가 혼란스럽다.
                ExcelRow excelRow = ExcelRow.of(i + 1, values);
                if (!excelRow.isEmpty()) {
                    rows.add(excelRow);
                }
            }
            return new ExcelParseResult(headerRow, rows);

        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "엑셀 파일을 읽을 수 없습니다.");
        }
    }

    public ExcelParseResult read(InputStream input, ColumnMapping mapping) {
        return read(input, mapping, 0);
    }

    private List<String> readHeader(Row header) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < header.getLastCellNum(); i++) {
            headers.add(cellText(header.getCell(i)));
        }
        return headers;
    }

    /** 셀을 문자열로. 숫자 셀의 {@code .0} 꼬리와 날짜 서식을 여기서 흡수한다. */
    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> numericText(cell);
            default -> null;
        };
    }

    private String numericText(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(CELL_DATE);
        }
        double value = cell.getNumericCellValue();
        // 정수인데 "20260001.0"으로 나오는 걸 막는다
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * @param headerRow 실제 파일의 헤더. 오류 메시지에서 필드명을 헤더명으로 되돌릴 때 쓴다
     */
    public record ExcelParseResult(List<String> headerRow, List<ExcelRow> rows) {
    }
}
