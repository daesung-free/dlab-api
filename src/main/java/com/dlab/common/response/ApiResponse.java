package com.dlab.common.response;

import com.dlab.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

/**
 * 모든 컨트롤러 응답의 공통 포맷 (CLAUDE.md §7).
 * 성공: { "success": true, "data": ... }
 * 실패: { "success": false, "error": { "code": ..., "message": ... } }
 * 목록: { "success": true, "data": [...], "meta": { "page": ..., "totalElements": ... } }
 *
 * <p><b>적용 범위는 {@code /api/v1/**} 뿐이다.</b> 키오스크가 호출하는 DSA 호환 구획
 * ({@code /auth/**}, {@code /kiosk/**})은 {@code {code, message, data, ...}} 형태라
 * 이 래퍼를 적용하면 키오스크가 응답을 못 읽는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, PageMeta meta, ErrorBody error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /** 목록 응답. 페이징 정보를 meta에 담는다 — 앱 규약 A-C1이 이 형태를 전제한다. */
    public static <T> ApiResponse<T> success(T data, PageMeta meta) {
        return new ApiResponse<>(true, data, meta, null);
    }

    /** Page를 그대로 넘겨 본문(content)과 meta를 분리한다. */
    public static <T> ApiResponse<java.util.List<T>> from(Page<T> page) {
        return new ApiResponse<>(true, page.getContent(), PageMeta.of(page), null);
    }

    /** 응답 본문이 없는 성공. (accessor success()와 이름이 겹쳐 empty()로 둔다) */
    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, null, new ErrorBody(errorCode.name(), message));
    }

    /**
     * 목록 응답의 페이징 정보.
     * page는 0-based인 Spring Data 규약을 그대로 노출한다 — 클라이언트와 어긋나면
     * 오프바이원이 조용히 생기므로 변환하지 말 것.
     */
    public record PageMeta(
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {
        public static PageMeta of(Page<?> page) {
            return new PageMeta(
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.hasNext()
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message) {
    }
}
