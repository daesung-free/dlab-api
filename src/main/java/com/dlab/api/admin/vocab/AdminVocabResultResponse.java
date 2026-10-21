package com.dlab.api.admin.vocab;

import com.dlab.domain.vocab.service.VocabResultBoardService;
import com.dlab.domain.vocab.service.VocabWrongAnswerService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 통합관리 — D:VOCA 결과 조회 응답 (v1 7장). */
public final class AdminVocabResultResponse {

    private AdminVocabResultResponse() {
    }

    /**
     * 학생 한 줄.
     *
     * <p>★ <b>미응시와 0점이 구분된다.</b> {@code averageScore} 가 {@code null} 이면
     * <b>안 본 것</b>이고 0 이면 <b>보고 다 틀린 것</b>이다 — 전자는 독려할 일이고 후자는
     * 가르칠 일이다.
     */
    @Schema(name = "VocabResultStudentRow", description = "학생별 결과")
    public record StudentRow(
            Long enrollmentId,
            String studentNo,
            String studentName,
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName,
            @Schema(description = "기간 내 공개된 회차 수") int openCount,
            @Schema(description = "응시한 회차 수. ★ 재응시는 세지 않는다") int takenCount,
            @Schema(description = "참여율 %. 공개 회차가 없으면 null") Integer participationRate,
            @Schema(description = "정답률 평균 %. ★ 미응시 회차는 빼고 센다. 전부 미응시면 null")
            Integer averageScore,
            @Schema(description = "마지막 응시 정답률 %") Integer lastScore,
            LocalDate lastTakenOn) {

        public static StudentRow from(VocabResultBoardService.StudentRow r) {
            return new StudentRow(r.enrollmentId(), r.studentNo(), r.studentName(),
                    r.classId(), r.className(), r.homeroomTeacherId(), r.homeroomTeacherName(),
                    r.openCount(), r.takenCount(), r.participationRate(),
                    r.averageScore(), r.lastScore(), r.lastTakenOn());
        }
    }

    /** 유닛 한 줄 — ★ 금요일 누적은 제외한다(어느 유닛의 점수인지 가를 수 없다). */
    @Schema(name = "VocabResultUnitRow", description = "유닛별 결과 — 누적 회차 제외")
    public record UnitRow(
            Short unitNo,
            int roundCount,
            int attemptCount,
            @Schema(description = "정답률 평균 %. 응시가 없으면 null") Integer averageScore) {

        public static UnitRow from(VocabResultBoardService.UnitRow r) {
            return new UnitRow(r.unitNo(), r.roundCount(), r.attemptCount(), r.averageScore());
        }
    }

    /** 회차 한 줄. */
    @Schema(name = "VocabResultRoundRow", description = "회차별 결과")
    public record RoundRow(
            Long roundId,
            Integer roundNo,
            Short cycle,
            LocalDate openDate,
            boolean weekly,
            Short unitFrom,
            Short unitTo,
            @Schema(description = "대상 학생 수(재원생)") int targetCount,
            @Schema(description = "응시 수. ★ 재응시는 세지 않는다") int attemptCount,
            Integer averageScore) {

        public static RoundRow from(VocabResultBoardService.RoundRow r) {
            return new RoundRow(r.roundId(), r.roundNo(), r.cycle(), r.openDate(),
                    r.weekly(), r.unitFrom(), r.unitTo(),
                    r.targetCount(), r.attemptCount(), r.averageScore());
        }
    }

    /** 오답이 많은 단어 — ★ 주관식만이다(객관식 오답은 인정 표현이 될 수 없다). */
    @Schema(name = "VocabWrongWordRow", description = "단어별 오답 현황 — 주관식만")
    public record WrongWordRow(
            Long wordId,
            Short unitNo,
            String word,
            @Schema(description = "원고 뜻") String meaningRaw,
            @Schema(description = "채점된 주관식 답 수. ★ 적으면 오답률을 믿을 값이 아니다")
            int answeredCount,
            int wrongCount,
            Integer wrongRate) {

        public static WrongWordRow from(VocabWrongAnswerService.WordRow r) {
            return new WrongWordRow(r.wordId(), r.unitNo(), r.word(), r.meaningRaw(),
                    r.answeredCount(), r.wrongCount(), r.wrongRate());
        }
    }

    /**
     * 학생들이 쓴 틀린 답 — <b>같은 답끼리 묶은</b> 것이다.
     *
     * <p>★ 10명이 같은 표현을 썼으면 <b>인정해야 할 표현</b>이다.
     * {@code PUT /admin/vocab/words/{id}/meanings} 로 넣는다.
     */
    @Schema(name = "VocabWrongGivenRow", description = "학생이 쓴 틀린 답 — 같은 답끼리 묶음")
    public record WrongGivenRow(
            @Schema(description = "학생 답(정규화됨). 빈 값은 무응답이다") String given,
            int count) {

        public static WrongGivenRow from(VocabWrongAnswerService.GivenRow r) {
            return new WrongGivenRow(r.given(), r.count());
        }
    }
}
