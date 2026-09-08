package com.dlab.api.admin.lecture;

import com.dlab.domain.lecture.entity.LectureAttendanceStatus;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.entity.LectureType;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** 특강 요청 DTO. */
public final class LectureRequests {

    private LectureRequests() {
    }

    public record LectureCreate(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") Integer year,
            LectureType lectureType,
            @NotBlank(message = "특강명은 필수입니다.") @Size(max = 100) String name,
            /** 특강 유형. {@code null}이면 유형 없이 만든다 — 마스터가 비어 있어도 개설은 돼야 한다. */
            Long categoryId) {
    }

    /** {@code null}은 "변경하지 않음"이다. */
    public record LectureUpdate(
            @Size(max = 100) String name,
            @Size(max = 30) String code,
            String description,
            @Positive(message = "정원은 1명 이상이어야 합니다.") Integer capacity,
            Instant applyFrom,
            Instant applyTo,
            LocalDate startDate,
            LocalDate endDate,
            @PositiveOrZero Integer fee,
            /** 담당 강사. {@code null}은 "변경하지 않음"이라 <b>해제는 이 값으로 못 한다.</b> */
            Long teacherId,
            /** 특강 유형. 강사와 같은 규칙이다 — {@code null}은 "변경하지 않음". */
            Long categoryId) {
    }

    public record LectureChangeStatus(@NotNull(message = "상태는 필수입니다.") LectureStatus status) {
    }

    public record ChangeVisible(@NotNull(message = "노출 여부는 필수입니다.") Boolean visible) {
    }

    public record AddSession(
            @NotNull(message = "회차 날짜는 필수입니다.") LocalDate sessionDate,
            LocalTime startTime,
            LocalTime endTime,
            @Size(max = 50) String room) {
    }

    public record MarkAttendance(
            @NotNull(message = "신청 ID는 필수입니다.") Long applicationId,
            @NotNull(message = "출석 상태는 필수입니다.") LectureAttendanceStatus status,
            @Size(max = 200) String memo) {
    }
}
