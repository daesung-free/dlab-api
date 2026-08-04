package com.dlab.api.admin.master;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public final class MasterRequests {

    private MasterRequests() {
    }

    public record CreateDepartment(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") @Min(2000) @Max(2100) Integer year,
            @NotBlank(message = "학과명은 필수입니다.") @Size(max = 50) String name) {
    }

    /** 전년도 복사. 원본·대상 연도를 명시로 받는다 — "작년"을 서버가 추정하면 연말에 어긋난다. */
    public record CopyYear(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "원본 연도는 필수입니다.") @Min(2000) @Max(2100) Integer fromYear,
            @NotNull(message = "대상 연도는 필수입니다.") @Min(2000) @Max(2100) Integer toYear) {
    }

    public record Rename(
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 50) String name) {
    }

    public record CreateTrack(
            @NotBlank(message = "계열명은 필수입니다.") @Size(max = 20) String name) {
    }

    public record CreateLocker(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "사물함 번호는 필수입니다.") @Size(max = 20) String lockerNo) {
    }

    public record AssignLocker(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId) {
    }

    public record GrantScholarship(
            @NotNull(message = "등록 건은 필수입니다.") Long enrollmentId,
            @NotBlank(message = "장학 종류는 필수입니다.") @Size(max = 20) String scholarshipType,
            @NotNull(message = "할인율은 필수입니다.")
            @DecimalMin(value = "0.0", message = "할인율은 0 이상이어야 합니다.")
            @DecimalMax(value = "100.0", message = "할인율은 100 이하여야 합니다.")
            BigDecimal discountRate) {
    }
}
