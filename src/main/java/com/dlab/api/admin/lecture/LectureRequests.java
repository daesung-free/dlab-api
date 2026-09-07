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
            @NotBlank(message = "특강명은 필수입니다.") @Size(max = 100) String name) {
    }

    /**
     * {@code null}은 "변경하지 않음"이다.
     *
     * @param instructorName 담당 강사명. <b>직원 목록과 묶지 않는다</b> — 외부 강사가
     *                       있어 FK로 박으면 그런 강사는 등록 자체를 못 한다
     */
    public record LectureUpdate(
            @Size(max = 100) String name,
            @Size(max = 30) String code,
            @Size(max = 50) String instructorName,
            String description,
            @Positive(message = "정원은 1명 이상이어야 합니다.") Integer capacity,
            Instant applyFrom,
            Instant applyTo,
            LocalDate startDate,
            LocalDate endDate,
            @PositiveOrZero Integer fee) {
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
