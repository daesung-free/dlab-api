package com.dlab.api.admin.vocab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 통합관리 — D:VOCA 일정 요청 (v1 7장). */
public final class AdminVocabScheduleRequest {

    private AdminVocabScheduleRequest() {
    }

    /**
     * 지점 시작일·출제 설정.
     *
     * <p>★ 출제 설정은 <b>업체 확인 중</b>이다(v1 Q2) — 비워 보내면 기존 값을 그대로 둔다.
     */
    @Schema(name = "VocabScheduleSaveRequest", description = "지점 시작일·출제 설정")
    public record SaveConfig(
            @Schema(description = "지점. 비우면 내 지점") Long academyId,
            @Schema(description = "1회독 1유닛이 공개되는 첫 시험일. 첫 등록에는 필수")
            LocalDate startDate,
            @Schema(description = "평일 회차 문항 수(기본 20)") Short questionCount,
            @Schema(description = "★ 금요일 누적 상한(기본 40). 3회독 누적은 최대 800단어다")
            Short weeklyQuestionCap,
            @Schema(description = "객관식 보기 수(기본 4)") Short choiceCount,
            @Schema(description = "객관식 비율 %(기본 50)") Short multipleChoiceRate) {
    }

    /** 시험 없는 날 — 공휴일·모의고사 등. ★ 그날은 진도가 밀린다(v1 5.1). */
    @Schema(name = "VocabNoExamDayRequest", description = "시험 없는 날")
    public record NoExamDay(
            @Schema(description = "지점. 비우면 내 지점") Long academyId,
            @NotNull LocalDate date,
            @Schema(description = "사유(모의고사 등)") String reason) {
    }
}
