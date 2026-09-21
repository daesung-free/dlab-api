package com.dlab.api.admin.qna;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalTime;

/** 질의응답 대면 요청 DTO. */
public final class QnaRequests {

    private QnaRequests() {
    }

    /**
     * 가능 타임 일괄 개설.
     *
     * <p><b>간격을 요청으로 받는다</b> — ▷[0803] "현재 15분, 변동 가능"이라 스키마에 두지 않았다.
     */
    public record QnaOpenSlots(
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "연도는 필수입니다.") Integer year,
            @NotNull(message = "날짜는 필수입니다.") LocalDate date,
            @NotNull(message = "시작 시각은 필수입니다.") LocalTime from,
            @NotNull(message = "종료 시각은 필수입니다.") LocalTime to,
            @NotNull(message = "간격은 필수입니다.") @Min(1) @Max(240) Integer intervalMinutes,
            /** 담당 선생님. {@code null}이면 미배정으로 열어두고 나중에 정한다. */
            Long teacherId,
            @Size(max = 50) String room,
            /** 슬롯당 인원. 생략하면 1(1:1 상담). */
            @Positive Integer capacity) {
    }

    public record ChangeClosed(@NotNull(message = "마감 여부는 필수입니다.") Boolean closed) {
    }

    public record QnaAssign(Long teacherId, @Size(max = 50) String room,
                         @Size(max = 200) String memo) {
    }

    /** 학생 예약. 질문은 선택 — ▷[0803] 회신 항목이라 시트 미반영이다. */
    /** @param subject 과목(자유 입력, 선택). 사진은 예약 후 {@code .../photos}로 따로 올린다 */
    public record QnaReserve(@Size(max = 500) String question, @Size(max = 30) String subject) {
    }
}
