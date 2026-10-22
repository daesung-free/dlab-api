package com.dlab.api.app.vocab;

import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabAttempt;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.service.VocabExamService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 앱 — D:VOCA 응답. */
public final class AppVocabResponse {

    private AppVocabResponse() {
    }

    /** 회차 목록 한 줄 (v1 6장). */
    @Schema(name = "VocabRoundView", description = "공개된 회차")
    public record Round(
            Long roundId,
            Integer roundNo,
            @Schema(description = "회독 1~3") Short cycle,
            LocalDate openDate,
            @Schema(description = "금요일 누적 시험인가") boolean weekly,
            Short unitFrom,
            Short unitTo,
            @Schema(description = "응시했는가") boolean attempted,
            @Schema(description = "대표 점수. ★ 최초 제출 건이다(v1 Q5). 미응시면 null")
            Short score,
            @Schema(description = "★ 입학 전에 공개된 회차. 응시는 선택이다(v1 5.5)")
            boolean beforeEnrollment) {

        public static Round of(VocabExamRound round, VocabAttempt representative,
                               boolean beforeEnrollment) {
            return new Round(round.getId(), round.getRoundNo(), round.getCycle(),
                    round.getOpenDate(), round.isWeekly(),
                    round.getUnitFrom(), round.getUnitTo(),
                    representative != null, representative == null ? null
                    : representative.getScore(), beforeEnrollment);
        }
    }

    /**
     * 응시 시작.
     *
     * <p>★ <b>정답이 없다</b>(v1 4.2) — 정답·정오는 제출 뒤에만 내려간다.
     */
    @Schema(name = "VocabStartView", description = "응시 시작 — ★ 정답을 내리지 않는다")
    public record Start(Long attemptId, List<Question> questions) {

        public static Start from(VocabExamService.StartResult result) {
            return new Start(result.attemptId(),
                    result.questions().stream().map(Question::from).toList());
        }
    }

    @Schema(name = "VocabQuestionView", description = "문항 — ★ 정답 위치가 없다")
    public record Question(
            Short seqNo,
            Long wordId,
            @Schema(description = "영어 단어") String word,
            @Schema(description = "MC=객관식 · SA=주관식") VocabAnswer.Type type,
            @Schema(description = "객관식 보기. ★ 섞여 있다. 주관식은 빈 배열")
            List<String> choices) {

        public static Question from(VocabExamService.Question q) {
            return new Question(q.seqNo(), q.wordId(), q.word(), q.type(), q.choices());
        }
    }

    /** 결과 (v1 6장) — 점수·문항별 정오·정답. */
    @Schema(name = "VocabResultView", description = "응시 결과")
    public record Result(
            Long attemptId,
            Long roundId,
            Instant startedAt,
            @Schema(description = "★ null 이면 미제출이다") Instant submittedAt,
            @Schema(description = "정답 수. ★ null 이면 미제출 — 0 과 다르다") Short score,
            Short totalCount,
            @Schema(description = "재응시 건인가") boolean retry,
            List<Graded> items) {

        public static Result of(VocabAttempt attempt, List<VocabAnswer> answers) {
            return new Result(attempt.getId(), attempt.getRound().getId(),
                    attempt.getStartedAt(), attempt.getSubmittedAt(), attempt.getScore(),
                    attempt.getTotalCount(), attempt.isRetry(),
                    // ★ 제출 전에는 정답을 보여주지 않는다 — 열어 두면 시작만 하고 답을 본다
                    attempt.isSubmitted()
                            ? answers.stream().map(Graded::from).toList() : List.of());
        }
    }

    @Schema(name = "VocabGradedView", description = "채점된 문항")
    public record Graded(
            Short seqNo,
            String word,
            VocabAnswer.Type type,
            @Schema(description = "학생이 낸 답. 무응답이면 빈 값") String given,
            Boolean correct,
            @Schema(description = "정답(원고 뜻). ★ 제출 뒤에만 내려간다") String answer) {

        public static Graded from(VocabAnswer a) {
            return new Graded(a.getSeqNo(), a.getWord().getWord(), a.getType(),
                    a.getGiven(), a.getCorrect(), a.getWord().getMeaningRaw());
        }
    }

    /** 내 기록 한 줄 (v1 6장). */
    @Schema(name = "VocabHistoryView", description = "응시 이력")
    public record History(
            Long attemptId,
            Long roundId,
            Integer roundNo,
            Short cycle,
            Short unitFrom,
            Short unitTo,
            boolean weekly,
            Instant startedAt,
            Instant submittedAt,
            Short score,
            Short totalCount,
            boolean retry) {

        public static History from(VocabAttempt a) {
            var r = a.getRound();
            return new History(a.getId(), r.getId(), r.getRoundNo(), r.getCycle(),
                    r.getUnitFrom(), r.getUnitTo(), r.isWeekly(),
                    a.getStartedAt(), a.getSubmittedAt(), a.getScore(),
                    a.getTotalCount(), a.isRetry());
        }
    }
}
