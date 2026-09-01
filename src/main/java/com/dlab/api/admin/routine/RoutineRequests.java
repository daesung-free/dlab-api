package com.dlab.api.admin.routine;

import com.dlab.domain.routine.entity.RoutineResultStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** 데일리 루틴 요청 DTO. */
public final class RoutineRequests {

    private RoutineRequests() {
    }

    public record RoutineCreate(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") Integer year,
            @NotNull(message = "월은 필수입니다.") @Min(1) @Max(12) Integer month,
            /** {@code null}이면 지점 공통 루틴이다. */
            Long classId,
            @NotBlank(message = "루틴명은 필수입니다.") @Size(max = 100) String name,
            @Size(max = 30) String subject,
            /** 만점. 0이면 점수 없이 완료/미완료만 본다. */
            @PositiveOrZero Integer maxScore,
            Boolean recommended,
            Integer sortOrder) {
    }

    /** {@code null}은 "변경하지 않음"이다. */
    public record RoutineUpdate(
            @Size(max = 100) String name,
            @Size(max = 30) String subject,
            @PositiveOrZero Integer maxScore,
            Boolean recommended,
            Integer sortOrder) {
    }

    public record CopyFromPreviousMonth(
            @NotNull Long academyId,
            @NotNull Integer year,
            @NotNull @Min(1) @Max(12) Integer month) {
    }

    /**
     * 반 단위 일괄 입력.
     *
     * <p>시트가 <b>"반 단위 그리드 일괄 입력 필수(개별 폼 금지)"</b>를 요구해서
     * 한 건씩 저장하는 엔드포인트를 두지 않는다.
     */
    public record SaveResults(
            @NotEmpty(message = "입력할 결과가 없습니다.") @Valid List<ResultRow> results) {
    }

    public record ResultRow(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotNull(message = "상태는 필수입니다.") RoutineResultStatus status,
            /** 학생 가채점. 교사 검수 점수와 별도로 보관된다 */
            Integer selfScore,
            Integer reviewedScore,
            @Size(max = 200) String memo) {
    }
}
