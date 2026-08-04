package com.dlab.common.excel;

/**
 * 행 단위 오류.
 *
 * @param rowNumber 엑셀 기준 행 번호(1-based). 화면에서 "3행: ..."으로 그대로 보여준다 —
 *                  0-based로 바꾸면 사용자가 엑셀에서 그 행을 못 찾는다
 * @param field     내부 필드명. 화면은 이걸 헤더명으로 되돌려 표시한다
 */
public record RowError(int rowNumber, String field, String message) {
}
