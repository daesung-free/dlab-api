package com.dlab.common.excel;

import java.util.List;

/**
 * 업로드 미리보기 결과. 요구사항 F-4.1-2의 {@code ImportPreviewResult}에 대응한다.
 *
 * <p><b>오류행이 있어도 정상행은 반영할 수 있다.</b> 100건 중 3건이 틀렸다고 전부
 * 되돌리면 사용자가 파일을 고쳐 다시 올려야 하는데, 실무에서는 나머지 97건을 먼저
 * 넣고 3건만 손보는 쪽이 훨씬 낫다(실행가이드 완료기준: "오류행 표시 후 정상행만 반영").
 *
 * @param importId  확정 요청에 쓰는 식별자. 파일을 두 번 올리지 않게 파싱 결과를 보관한다
 * @param totalRows 빈 줄을 뺀 전체 행 수
 * @param validRows 반영 가능한 행 수
 * @param errors    행 단위 오류. 한 행에 여러 개일 수 있다
 */
public record ImportPreview<T>(
        String importId,
        int totalRows,
        int validRows,
        int errorRows,
        List<RowError> errors,
        List<T> valid
) {

    public static <T> ImportPreview<T> of(String importId, int totalRows,
                                          List<T> valid, List<RowError> errors) {
        // 한 행에 오류가 여럿일 수 있으므로 행 번호로 세야 정확하다
        int errorRows = (int) errors.stream().map(RowError::rowNumber).distinct().count();
        return new ImportPreview<>(importId, totalRows, valid.size(), errorRows, errors, valid);
    }

    public boolean hasError() {
        return errorRows > 0;
    }

    /** 반영할 게 하나도 없으면 확정 단계로 넘길 이유가 없다. */
    public boolean isApplicable() {
        return validRows > 0;
    }
}
