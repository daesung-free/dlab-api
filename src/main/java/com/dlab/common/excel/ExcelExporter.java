package com.dlab.common.excel;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 목록 → 엑셀 바이트.
 *
 * <p><b>{@link SXSSFWorkbook}(스트리밍)을 쓴다.</b> 명단 출력(F-4.9)은 재원생 전체가
 * 대상이라 수천 행이 나올 수 있는데, 일반 {@code XSSFWorkbook}은 전 행을 메모리에
 * 들고 있어 목록이 커지면 그대로 OOM으로 간다.
 */
@Component
public class ExcelExporter {

    /** 이 행 수를 넘으면 디스크로 흘려보낸다. */
    private static final int ROW_ACCESS_WINDOW = 500;

    /**
     * @param rowMapper 데이터 한 건 → 셀 문자열 목록. {@code mapping.exportHeaders()} 순서와 맞춰야 한다
     */
    public <T> byte[] export(String sheetName, ColumnMapping mapping,
                             List<T> data, Function<T, List<String>> rowMapper) {
        return export(sheetName, mapping.exportHeaders(), data, rowMapper);
    }

    public <T> byte[] export(String sheetName, List<String> headers,
                             List<T> data, Function<T, List<String>> rowMapper) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(sheetName);
            writeHeader(workbook, sheet, headers);

            int rowIndex = 1;
            for (T item : data) {
                Row row = sheet.createRow(rowIndex++);
                List<String> cells = rowMapper.apply(item);
                for (int i = 0; i < cells.size(); i++) {
                    row.createCell(i).setCellValue(cells.get(i) == null ? "" : cells.get(i));
                }
            }

            workbook.write(output);
            // 스트리밍 워크북은 임시파일을 남긴다. 안 지우면 서버 디스크가 찬다.
            workbook.dispose();
            return output.toByteArray();

        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "엑셀 생성에 실패했습니다.");
        }
    }

    private void writeHeader(Workbook workbook, Sheet sheet, List<String> headers) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);

        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            var cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(style);
        }
    }
}
