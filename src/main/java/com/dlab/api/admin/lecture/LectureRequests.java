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
            /**
             * 유형 세분류. 비우면 유형 없이 만든다.
             *
             * <p>등록에서도 받는 이유는 <b>화면이 한 번에 저장하기 때문</b>이다.
             * 수정에만 있으면 등록 폼에서 유형을 골라도 저장이 두 번 나가고,
             * 두 번째가 실패하면 유형 없는 특강이 남는다.
             */
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
            /** 유형 세분류(단과·실전·해설). {@code null}은 "변경하지 않음"이다. */
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
