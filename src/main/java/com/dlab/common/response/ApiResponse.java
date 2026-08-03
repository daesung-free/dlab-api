package com.dlab.common.response;

import com.dlab.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 컨트롤러 응답의 공통 포맷 (CLAUDE.md §7).
 * 성공: { "success": true, "data": ... }
 * 실패: { "success": false, "error": { "code": ..., "message": ... } }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 응답 본문이 없는 성공. (accessor success()와 이름이 겹쳐 empty()로 둔다) */
    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(errorCode.name(), message));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message) {
    }
}
