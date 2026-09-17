package com.dlab.api.admin.lecture;

import com.dlab.common.web.Patch;
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
    /**
     * 특강 수정.
     *
     * <h2>비울 수 있는 값은 {@link Patch} 로 받는다</h2>
     * 보내지 않으면 건드리지 않고, {@code null} 을 <b>명시적으로</b> 보내면 비운다.
     * 그냥 {@code Integer} 로 두면 둘이 구분되지 않아 <b>"정원 제한을 없앤다"가 불가능</b>했다 —
     * {@code {"capacity": null}} 이 200 을 받고도 아무 일도 하지 않았다.
     *
     * <pre>
     *   {}                    건드리지 않음
     *   {"capacity": 30}      30 으로
     *   {"capacity": null}    제한 없음으로
     * </pre>
     *
     * <p><b>이름과 코드는 상자에 담지 않았다</b> — 없으면 목록에 빈 줄이 생기고 신청 화면이
     * 무엇을 신청하는지 알 수 없다. 값을 바꾸는 것만 된다.
     */
    public record LectureUpdate(
            @Size(max = 100) String name,
            @Size(max = 30) String code,
            /** 안내 문구. 비우면 상세에서 설명이 사라진다 */
            Patch<String> description,
            /** 비우면 <b>정원 제한 없음</b>이다 */
            Patch<Integer> capacity,
            /** 비우면 신청 시작 제한 없음 */
            Patch<Instant> applyFrom,
            /** 비우면 신청 마감 제한 없음 */
            Patch<Instant> applyTo,
            Patch<LocalDate> startDate,
            Patch<LocalDate> endDate,
            Patch<Integer> fee,
            /** 담당 강사. 비우면 <b>미지정</b>이 된다 */
            Patch<Long> teacherId,
            /** 유형 세분류(단과·실전·해설). 비우면 분류 없음 */
            Patch<Long> categoryId) {
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
