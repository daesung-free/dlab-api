package com.dlab.common.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엑셀 읽기. <b>운영팀이 쓰는 파일은 서식이 제각각</b>이라 그 변형들을 통과시키는지 본다 —
 * 여기서 막히면 사용자는 "왜 안 올라가는지" 알 수 없다.
 */
class ExcelReaderTest {

    private final ExcelReader reader = new ExcelReader();

    private final ColumnMapping mapping = ColumnMapping.builder()
            .required("studentNo", "학번", "학생번호")
            .required("name", "이름", "성명")
            .optional("admissionDate", "등원일")
            .optional("phone", "연락처", "전화번호");

    /** 헤더 + 데이터로 xlsx 바이트를 만든다. */
    private byte[] workbook(List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("s");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    Object v = cells.get(c);
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ExcelReader.ExcelParseResult read(byte[] bytes) {
        return reader.read(new ByteArrayInputStream(bytes), mapping);
    }

    @Test
    @DisplayName("헤더명으로 컬럼을 찾는다 — 순서가 바뀌어도 동작한다")
    void findsByHeaderNameNotPosition() {
        byte[] file = workbook(
                List.of("이름", "연락처", "학번"),          // 순서를 섞었다
                List.of(List.of("김민지", "010-1111-2222", "2026-0001")));

        var rows = read(file).rows();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).text("studentNo")).isEqualTo("2026-0001");
        assertThat(rows.get(0).text("name")).isEqualTo("김민지");
    }

    @Test
    @DisplayName("별칭 헤더도 인식한다 — 같은 뜻인데 표기가 다른 경우가 실제로 많다")
    void acceptsAliasHeaders() {
        byte[] file = workbook(
                List.of("학생번호", "성명", "전화번호"),
                List.of(List.of("2026-0001", "김민지", "010-1111-2222")));

        var rows = read(file).rows();

        assertThat(rows.get(0).text("studentNo")).isEqualTo("2026-0001");
        assertThat(rows.get(0).text("phone")).isEqualTo("010-1111-2222");
    }

    @Test
    @DisplayName("헤더의 공백·대소문자를 무시한다 — 눈에 안 보이는 공백 때문에 막히면 원인을 못 찾는다")
    void ignoresWhitespaceInHeader() {
        byte[] file = workbook(
                List.of(" 학 번 ", "이름 "),
                List.of(List.of("2026-0001", "김민지")));

        assertThat(read(file).rows().get(0).text("studentNo")).isEqualTo("2026-0001");
    }

    @Test
    @DisplayName("필수 컬럼이 없으면 파일 자체를 거부한다 — 행 검증이 무의미하다")
    void rejectsFileMissingRequiredColumn() {
        byte[] file = workbook(List.of("이름"), List.of(List.of("김민지")));

        assertThatThrownBy(() -> read(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("학번");
    }

    @Test
    @DisplayName("★ 숫자 셀의 .0 꼬리를 제거한다 — 학번이 20260001.0으로 들어가면 안 된다")
    void stripsTrailingZeroFromNumericCell() {
        byte[] file = workbook(
                List.of("학번", "이름"),
                List.of(List.of(20260001, "김민지")));

        assertThat(read(file).rows().get(0).text("studentNo")).isEqualTo("20260001");
    }

    @Test
    @DisplayName("빈 줄은 건너뛴다 — 엑셀 끝의 서식만 남은 행을 오류로 잡으면 혼란스럽다")
    void skipsBlankRows() {
        byte[] file = workbook(
                List.of("학번", "이름"),
                java.util.Arrays.asList(
                        List.of("2026-0001", "김민지"),
                        java.util.Arrays.asList(null, null),
                        List.of("2026-0002", "박서준")));

        assertThat(read(file).rows()).hasSize(2);
    }

    @Test
    @DisplayName("행 번호는 엑셀 기준 1-based다 — 화면에서 그 행을 찾을 수 있어야 한다")
    void rowNumberIsExcelBased() {
        byte[] file = workbook(
                List.of("학번", "이름"),
                List.of(List.of("2026-0001", "김민지")));

        // 헤더가 1행이므로 첫 데이터는 2행
        assertThat(read(file).rows().get(0).rowNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("날짜는 여러 표기를 받는다 — 운영팀 엑셀마다 서식이 다르다")
    void parsesMultipleDateFormats() {
        for (String value : List.of("2026-03-02", "2026/03/02", "2026.03.02", "20260302")) {
            byte[] file = workbook(
                    List.of("학번", "이름", "등원일"),
                    List.of(List.of("2026-0001", "김민지", value)));

            ExcelRow row = read(file).rows().get(0);

            assertThat(row.date("admissionDate", "등원일"))
                    .as("입력값 %s", value)
                    .isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(row.hasError()).isFalse();
        }
    }

    @Test
    @DisplayName("변환 실패는 예외가 아니라 오류로 쌓인다 — 첫 오류에서 멈추면 하나씩만 발견하게 된다")
    void collectsErrorsInsteadOfThrowing() {
        byte[] file = workbook(
                List.of("학번", "이름", "등원일"),
                List.of(java.util.Arrays.asList("2026-0001", null, "날짜아님")));

        ExcelRow row = read(file).rows().get(0);
        row.requiredText("name", "이름");
        row.date("admissionDate", "등원일");

        assertThat(row.errors()).hasSize(2);
        assertThat(row.errors()).extracting(RowError::rowNumber).containsOnly(2);
    }
}
