package com.dlab.api.app.grade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** 성적 입력 DTO. */
public final class GradeRequests {

    private GradeRequests() {
    }

    /**
     * 내신 — 주요교과평균.
     *
     * <p>칸이 하나뿐이다. 신상기록부 실물이 그렇다 — 학년별 항목도, 학기 단위도,
     * 등급/원점수 구분도 없다.
     *
     * <p>{@code null}을 허용한다. 아직 모르는 학생이 모의고사만 먼저 내는 경우가 있다.
     */
    public record SchoolRecord(
            @DecimalMin(value = "0.0", message = "내신 평균은 0 이상이어야 합니다.")
            @DecimalMax(value = "999.99", message = "내신 평균 값이 너무 큽니다.")
            BigDecimal mainSubjectAverage) {
    }

    /**
     * 모의고사 성적.
     *
     * <p><b>보낸 회차만 교체된다.</b> 6월 성적만 아는 학생이 9월·10월까지 채워야
     * 저장되는 구조면 아무것도 못 낸다.
     */
    public record ExamScores(
            @NotEmpty(message = "입력한 성적이 없습니다.")
            @Valid List<Score> scores) {
    }

    /**
     * 과목 한 칸. 세 값 모두 {@code null}일 수 있다 —
     * 미응시·절대평가(한국사)·기억나지 않는 경우가 실제로 있다.
     *
     * <p><b>양식에 없는 칸은 서버가 버린다.</b> 한국사 표준점수처럼 존재하지 않는 값이
     * 저장되면 평균이 조용히 오염된다.
     */
    public record Score(
            @NotNull(message = "과목을 지정해야 합니다.") Long examSubjectId,
            @Min(0) @Max(200) Short standardScore,
            @Min(0) @Max(100) Short percentile,
            @Min(1) @Max(9) Short gradeLevel) {
    }

    /**
     * 성적을 모른다고 체크.
     *
     * <p>0으로 채우게 두면 <b>통계에서 진짜 0점과 구분되지 않는다.</b> 사유를 남겨야
     * 나중에 상담 교사가 "왜 없는지"를 물어볼 수 있다.
     */
    public record SkipExams(
            @NotBlank(message = "성적을 입력하지 않는 사유를 적어주세요.")
            @Size(max = 200) String reason) {
    }
}
