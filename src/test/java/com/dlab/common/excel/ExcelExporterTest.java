package com.dlab.common.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Export → Import 왕복. <b>우리가 내보낸 파일을 우리가 다시 읽을 수 있어야 한다</b> —
 * 실무에서 "내려받아 수정 후 다시 올리기"가 가장 흔한 흐름이다.
 */
class ExcelExporterTest {

    private final ExcelExporter exporter = new ExcelExporter();
    private final ExcelReader reader = new ExcelReader();

    private final ColumnMapping mapping = ColumnMapping.builder()
            .required("studentNo", "학번")
            .required("name", "이름")
            .optional("phone", "연락처");

    private record Student(String studentNo, String name, String phone) {
    }

    @Test
    @DisplayName("★ 내보낸 파일을 다시 읽을 수 있다 — 내려받아 수정 후 재업로드가 실무 흐름이다")
    void roundTrip() {
        List<Student> data = List.of(
                new Student("2026-0001", "김민지", "010-1111-2222"),
                new Student("2026-0002", "박서준", null));

        byte[] file = exporter.export("학생", mapping, data,
                s -> java.util.Arrays.asList(s.studentNo(), s.name(), s.phone()));

        var rows = reader.read(new ByteArrayInputStream(file), mapping).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).text("studentNo")).isEqualTo("2026-0001");
        assertThat(rows.get(0).text("name")).isEqualTo("김민지");
        assertThat(rows.get(1).text("phone")).isNull();
    }

    @Test
    @DisplayName("헤더는 매핑의 대표 표기를 순서대로 쓴다")
    void headerUsesPrimaryLabel() {
        byte[] file = exporter.export("학생", mapping, List.<Student>of(), s -> List.of());

        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            var header = wb.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("학번");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("이름");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("연락처");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("데이터가 없어도 헤더만 있는 파일이 나온다 — 양식 다운로드로 쓸 수 있다")
    void emptyDataProducesTemplate() {
        byte[] file = exporter.export("학생", mapping, List.<Student>of(), s -> List.of());

        assertThat(file).isNotEmpty();
        assertThat(reader.read(new ByteArrayInputStream(file), mapping).rows()).isEmpty();
    }

    @Test
    @DisplayName("미리보기는 오류행을 행 번호 기준으로 센다 — 한 행에 오류가 여럿일 수 있다")
    void previewCountsErrorRowsNotErrors() {
        var errors = List.of(
                new RowError(2, "name", "이름은 필수입니다."),
                new RowError(2, "admissionDate", "날짜 형식 오류"),
                new RowError(5, "name", "이름은 필수입니다."));

        var preview = ImportPreview.of("imp-1", 10, List.of("a", "b"), errors);

        assertThat(preview.errors()).hasSize(3);
        assertThat(preview.errorRows()).isEqualTo(2);   // 2행, 5행
        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(preview.hasError()).isTrue();
        assertThat(preview.isApplicable()).isTrue();    // 오류가 있어도 정상행은 반영 가능
    }
}
