package com.dlab.api.admin.student;

import com.dlab.common.excel.ImportPreview;
import com.dlab.domain.user.service.StudentImportService.ParsedStudent;

import java.util.List;

/**
 * 엑셀 업로드 결과. 미리보기와 반영이 <b>같은 형태</b>를 돌려준다 —
 * 화면이 하나의 표로 두 단계를 다 보여줄 수 있어야 한다.
 */
public record ImportResponse(
        int totalRows,
        int validRows,
        int errorRows,
        List<StudentImportRow> rows,
        List<Error> errors) {

    /** 반영될(또는 반영된) 행. {@code existing}이면 새로 만들지 않고 기존 학생을 갱신한다. */
    public record StudentImportRow(int rowNumber, String name, String grade, String track, boolean existing) {
    }

    /** 오류. <b>행 번호는 엑셀 기준 1-based</b>라 사용자가 그 행을 바로 찾을 수 있다. */
    public record Error(int rowNumber, String field, String message) {
    }

    public static ImportResponse from(ImportPreview<ParsedStudent> preview) {
        return new ImportResponse(
                preview.totalRows(),
                preview.validRows(),
                preview.errorRows(),
                preview.valid().stream()
                        .map(p -> new StudentImportRow(p.rowNumber(), p.name(),
                                p.grade() == null ? null : p.grade().name(),
                                p.track() == null ? null : p.track().name(),
                                p.existing()))
                        .toList(),
                preview.errors().stream()
                        .map(e -> new Error(e.rowNumber(), e.field(), e.message()))
                        .toList());
    }
}
