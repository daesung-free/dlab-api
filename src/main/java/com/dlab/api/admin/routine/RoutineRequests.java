package com.dlab.api.admin.routine;

import com.dlab.domain.routine.entity.RoutineResultStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.YearMonth;
import java.util.List;

/** 데일리 루틴 요청 DTO. */
public final class RoutineRequests {

    /** {@code yyyy-MM}. 전 엔드포인트가 같은 형식을 쓴다. */
    static final String MONTH_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";
    static final String MONTH_MESSAGE = "월은 yyyy-MM 형식이어야 합니다.";

    private RoutineRequests() {
    }

    /** @param month 대상 월. {@code yyyy-MM} */
    public record RoutineCreate(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotBlank(message = "월은 필수입니다.")
            @Pattern(regexp = MONTH_PATTERN, message = MONTH_MESSAGE) String month,
            /** {@code null}이면 지점 공통 루틴이다. */
            Long classId,
            @NotBlank(message = "루틴명은 필수입니다.") @Size(max = 100) String name,
            @Size(max = 30) String subject,
            /** 만점. 0이면 점수 없이 완료/미완료만 본다. */
            @PositiveOrZero Integer maxScore,
            Boolean recommended,
            Integer sortOrder) {

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }
    }

    /** {@code null}은 "변경하지 않음"이다. */
    public record RoutineUpdate(
            @Size(max = 100) String name,
            @Size(max = 30) String subject,
            @PositiveOrZero Integer maxScore,
            Boolean recommended,
            Integer sortOrder) {
    }

    /** @param month 복사해 넣을 <b>대상</b> 월. {@code yyyy-MM} */
    public record CopyFromPreviousMonth(
            @NotNull Long academyId,
            @NotBlank(message = "월은 필수입니다.")
            @Pattern(regexp = MONTH_PATTERN, message = MONTH_MESSAGE) String month) {

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }
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
