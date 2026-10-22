package com.dlab.api.app.vocab;

import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabAttempt;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.service.ExamItem;
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
            @Schema(description = "표제어 문항이면 채워진다") Long wordId,
            @Schema(description = "★ Part 3(구동사·숙어·혼동어휘) 문항이면 채워진다")
            Long phraseId,
            @Schema(description = "WORD=표제어 · PHRASE=구동사·숙어·혼동어휘")
            ExamItem.Part part,
            @Schema(description = "문제. Part 3 는 구동사·숙어 표현이다") String word,
            @Schema(description = "MC=객관식 · SA=주관식") VocabAnswer.Type type,
            @Schema(description = "객관식 보기. ★ 섞여 있다. 주관식은 빈 배열")
            List<String> choices) {

        public static Question from(VocabExamService.Question q) {
            return new Question(q.seqNo(), q.wordId(), q.phraseId(), q.part(),
                    q.word(), q.type(), q.choices());
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

        /**
         * ★ 발음·예문·유의어를 <b>가린</b> 결과. 지점 설정을 모르는 자리에서 쓴다.
         */
        public static Result of(VocabAttempt attempt, List<VocabAnswer> answers) {
            return of(attempt, answers, false);
        }

        /**
         * @param showWordDetail 결과 화면에 발음·예문·유의어를 보여주는가
         *                       (업체 확정 2026-10-22 — 지점 설정이다)
         */
        public static Result of(VocabAttempt attempt, List<VocabAnswer> answers,
                                boolean showWordDetail) {
            return new Result(attempt.getId(), attempt.getRound().getId(),
                    attempt.getStartedAt(), attempt.getSubmittedAt(), attempt.getScore(),
                    attempt.getTotalCount(), attempt.isRetry(),
                    // ★ 제출 전에는 정답을 보여주지 않는다 — 열어 두면 시작만 하고 답을 본다
                    attempt.isSubmitted()
                            ? answers.stream().map(a -> Graded.of(a, showWordDetail)).toList()
                            : List.of());
        }
    }

    /**
     * 채점된 문항.
     *
     * <h2>★ 발음·예문·유의어를 같이 내린다 (업체 확정 2026-10-22)</h2>
     * 틀린 단어를 그 자리에서 익히는 화면이라 뜻만으로는 모자란다. <b>노출은 지점
     * 설정으로 끌 수 있고</b>, 끄면 이 칸들이 {@code null} 로 내려간다 —
     * <b>데이터는 늘 저장돼 있다</b>(끄려고 2,000행을 다시 넣는 일이 없게).
     *
     * <p>⚠️ Part 3(구동사·숙어)에는 발음·유의어·파생어가 없다 — 예문만 있다.
     */
    @Schema(name = "VocabGradedView", description = "채점된 문항")
    public record Graded(
            Short seqNo,
            @Schema(description = "문제. Part 3 는 구동사·숙어 표현이다") String word,
            @Schema(description = "WORD=표제어 · PHRASE=구동사·숙어·혼동어휘")
            ExamItem.Part part,
            VocabAnswer.Type type,
            @Schema(description = "학생이 낸 답. 무응답이면 빈 값") String given,
            Boolean correct,
            @Schema(description = "정답(원고 뜻). ★ 제출 뒤에만 내려간다") String answer,
            @Schema(description = "발음. ★ 노출 설정이 꺼져 있거나 Part 3 면 null")
            String pronunciation,
            @Schema(description = "품사") String pos,
            @Schema(description = "영문 예문") String exampleEn,
            @Schema(description = "예문 해석") String exampleKo,
            @Schema(description = "유의어") String synonym,
            @Schema(description = "반의어") String antonym,
            @Schema(description = "파생어") String derivative) {

        public static Graded from(VocabAnswer a) {
            return of(a, false);
        }

        public static Graded of(VocabAnswer a, boolean showWordDetail) {
            var part = a.isPhrase() ? ExamItem.Part.PHRASE : ExamItem.Part.WORD;
            if (!showWordDetail) {
                return new Graded(a.getSeqNo(), a.prompt(), part, a.getType(),
                        a.getGiven(), a.getCorrect(), a.meaningRaw(),
                        null, null, null, null, null, null, null);
            }
            if (a.isPhrase()) {
                var ph = a.getPhrase();
                return new Graded(a.getSeqNo(), a.prompt(), part, a.getType(),
                        a.getGiven(), a.getCorrect(), a.meaningRaw(),
                        null, null, ph.getExampleEn(), ph.getExampleKo(),
                        null, null, null);
            }
            var w = a.getWord();
            return new Graded(a.getSeqNo(), a.prompt(), part, a.getType(),
                    a.getGiven(), a.getCorrect(), a.meaningRaw(),
                    w.getPronunciation(), w.getPos(), w.getExampleEn(), w.getExampleKo(),
                    w.getSynonym(), w.getAntonym(), w.getDerivative());
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
