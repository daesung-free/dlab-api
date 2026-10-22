package com.dlab.domain.vocab.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabAttempt;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabPhrase;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabAnswerRepository;
import com.dlab.domain.vocab.repository.VocabAttemptRepository;
import com.dlab.domain.vocab.repository.VocabExamRoundRepository;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabPhraseRepository;
import com.dlab.domain.vocab.repository.VocabScheduleConfigRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 응시·채점 (v1 4장 · 6장).
 *
 * <h2>★★ 정답은 서버 밖으로 나가지 않는다 (v1 4.2 · 9장)</h2>
 * 출제할 때 <b>보기 문구만</b> 내려보내고 정답 위치는 내리지 않는다. 채점은 제출을 받아
 * 서버가 한다 — 앱에 정답을 주면 <b>화면을 뜯어 보면 답이 보인다.</b>
 *
 * <h2>★ 학생은 자기 지점에 공개된 회차만 응시한다</h2>
 * 회차 id 를 받는 구조라, 지점 검증을 빼면 <b>id 만 바꿔 다른 지점 회차를 응시</b>할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class VocabExamService {

    private final VocabExamRoundRepository roundRepository;
    private final VocabScheduleConfigRepository configRepository;
    private final VocabAttemptRepository attemptRepository;
    private final VocabAnswerRepository answerRepository;
    private final VocabWordRepository wordRepository;
    private final VocabMeaningRepository meaningRepository;
    private final VocabPhraseRepository phraseRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 출제된 한 문항 — ★ 정답이 없다. 앱으로 그대로 나가는 모양이다.
     *
     * @param wordId   Part 1 이면 채워진다
     * @param phraseId Part 3(구동사·숙어·혼동어휘)이면 채워진다 — ★ 둘 중 하나만 있다
     * @param word     화면에 내는 문제. Part 3 는 구동사·숙어 표현이다
     */
    public record Question(short seqNo, Long wordId, Long phraseId, ExamItem.Part part,
                           String word, VocabAnswer.Type type, List<String> choices) {
    }

    /**
     * 응시 시작 (v1 6장).
     *
     * <p>★ <b>문항을 여기서 만들어 저장한다.</b> 제출할 때 다시 뽑으면 학생이 본 문항과
     * 달라진다 — 보기도 저장해야 <b>그때 무엇을 보고 골랐는지</b>가 남는다.
     *
     * <p>⚠️ 진행 중인 응시가 있으면 <b>그것을 돌려준다.</b> 새로 만들면 앱을 닫았다 열
     * 때마다 응시가 쌓이고, 어느 것이 그 회차 점수인지 알 수 없다.
     */
    @Transactional
    public StartResult start(Long enrollmentId, Long roundId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        VocabExamRound round = roundRepository.findById(roundId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOCAB_ROUND_NOT_FOUND));

        // ★ 자기 지점 회차인지 서버가 본다 — 빼면 id 만 바꿔 남의 지점 회차를 응시한다
        if (!round.getAcademy().getId().equals(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.VOCAB_ROUND_FORBIDDEN);
        }

        var inProgress = attemptRepository
                .findFirstByRoundIdAndEnrollmentIdAndSubmittedAtIsNullAndDeletedFalseOrderByStartedAtDesc(
                        roundId, enrollmentId);
        if (inProgress.isPresent()) {
            var attempt = inProgress.get();
            return new StartResult(attempt.getId(), questionsOf(attempt));
        }

        var config = configRepository
                .findByAcademyIdAndYearAndDeletedFalse(
                        round.getAcademy().getId(), round.getYear())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOCAB_SCHEDULE_NOT_CONFIGURED));

        List<ExamItem> pool = itemsInRange(round, config.isIncludePhrase());
        if (pool.isEmpty()) {
            // 0문항 시험을 만들면 학생이 제출만 해도 만점이 된다
            throw new BusinessException(ErrorCode.VOCAB_NO_WORDS_IN_RANGE);
        }

        // ★ 이미 제출한 건이 있으면 재응시다(v1 Q5) — 대표 점수는 최초 건이 지킨다
        boolean retry = attemptRepository
                .findFirstByRoundIdAndEnrollmentIdAndSubmittedAtNotNullAndDeletedFalseOrderBySubmittedAtAsc(
                        roundId, enrollmentId)
                .isPresent();

        Random random = new Random();
        // ★ 범위의 단어 수보다 설정값이 크면 단어 수로 줄어든다 — 같은 단어를 두 번 내지 않는다
        int count = Math.min(config.questionCountFor(round.isWeekly()), pool.size());
        List<ExamItem> picked = new ArrayList<>(pool);
        Collections.shuffle(picked, random);
        picked = picked.subList(0, count);

        var attempt = attemptRepository.save(new VocabAttempt(
                round.getAcademy(), round.getYear(), round, enrollment,
                Instant.now(clock), (short) count, retry));

        // ★★ 보기 추출에 필요한 것을 문항마다 조회하지 않는다 (v1 9장 — 2,500명 동시 접속)
        //
        //   ① 같은 유닛 단어는 **이미 읽은 pool** 을 유닛별로 묶어 쓴다. 문항마다
        //      findByUnitNo 를 부르면 문항 수만큼 쿼리가 난다(실측: 문항당 +1).
        //   ② 전체 단어는 **폴백에 들어갈 때만** 읽는다. 1단계에서 보기가 다 차면
        //      2,000건을 아예 읽지 않는다 — 그리고 한 번 읽으면 그 응시 안에서 재사용한다.
        Map<Short, List<VocabWord>> byUnit = pool.stream()
                .filter(i -> !i.isPhrase())
                .map(ExamItem::word)
                .collect(Collectors.groupingBy(VocabWord::getUnitNo));
        // ★ Part 3 도 같은 방식이다 — 이미 읽은 pool 을 유닛별로 묶어 쓴다
        Map<Short, List<VocabPhrase>> phrasesByUnit = pool.stream()
                .filter(ExamItem::isPhrase)
                .map(ExamItem::phrase)
                .collect(Collectors.groupingBy(VocabPhrase::getUnitNo));
        var allWords = new Supplier<Collection<VocabWord>>() {
            private Collection<VocabWord> cached;

            @Override
            public Collection<VocabWord> get() {
                if (cached == null) {
                    cached = wordRepository.findByDeletedFalse();
                }
                return cached;
            }
        };
        var allPhrases = new Supplier<Collection<VocabPhrase>>() {
            private Collection<VocabPhrase> cached;

            @Override
            public Collection<VocabPhrase> get() {
                if (cached == null) {
                    cached = phraseRepository.findByDeletedFalse();
                }
                return cached;
            }
        };

        int mcCount = Math.round(count * config.getMultipleChoiceRate() / 100f);

        short seq = 1;
        for (ExamItem item : picked) {
            boolean multipleChoice = seq <= mcCount;
            var type = multipleChoice ? VocabAnswer.Type.MC : VocabAnswer.Type.SA;
            List<String> choices = List.of();
            if (multipleChoice) {
                choices = item.isPhrase()
                        // ★ Part 3 오답은 Part 3 에서만 뽑는다 — 섞으면 길이만 봐도 답이 보인다
                        ? ChoicePicker.pickPhrase(item.phrase(),
                                phrasesByUnit.getOrDefault(item.unitNo(), List.of()),
                                allPhrases, config.getChoiceCount(), random).texts()
                        : ChoicePicker.pick(item.word(),
                                byUnit.getOrDefault(item.unitNo(), List.of()),
                                allWords, config.getChoiceCount(), random).texts();
            }
            var answer = item.isPhrase()
                    ? new VocabAnswer(attempt, item.phrase(), seq, type, choices)
                    : new VocabAnswer(attempt, item.word(), seq, type, choices);
            answerRepository.save(answer);
            attempt.addAnswer(answer);
            seq++;
        }

        return new StartResult(attempt.getId(), questionsOf(attempt));
    }

    /** 응시 시작 결과 — ★ 정답·정오가 없다. */
    public record StartResult(Long attemptId, List<Question> questions) {
    }

    /** 회차 목록 한 줄 — 공개된 회차 + 그 학생의 대표 응시. */
    public record RoundView(VocabExamRound round, VocabAttempt representative,
                            boolean beforeEnrollment) {
    }

    /**
     * 회차 목록 (v1 6장).
     *
     * <p>★ <b>입학 전 회차를 구분해서 내린다.</b> 중도 입학생은 그 회차를 <b>골라서 추가로
     * 응시</b>할 수 있고(v1 5.5) 필수가 아니다 — 구분이 없으면 앱이 「안 본 시험이 쌓였다」로
     * 보여 주고, 학생은 밀린 것으로 읽는다.
     *
     * <p>★ 점수는 <b>최초 제출 건</b>이다(v1 Q5).
     */
    @Transactional(readOnly = true)
    public List<RoundView> rounds(Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        LocalDate admission = enrollment.getAdmissionDate();

        return roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(
                        enrollment.getAcademy().getId(), enrollment.getYear())
                .stream()
                .map(round -> new RoundView(round,
                        attemptRepository
                                .findFirstByRoundIdAndEnrollmentIdAndSubmittedAtNotNullAndDeletedFalseOrderBySubmittedAtAsc(
                                        round.getId(), enrollmentId)
                                .orElse(null),
                        // ⚠️ 입학일이 없는 행이 있다 — 그때는 「입학 전」으로 볼 근거가 없다
                        admission != null && round.getOpenDate().isBefore(admission)))
                .toList();
    }

    /**
     * 제출·채점 (v1 4.2).
     *
     * <p>★ <b>객관식도 「고른 문구」로 받는다.</b> 보기 순서를 섞어 내리므로 인덱스는
     * 앱과 서버가 어긋날 수 있고, 그러면 <b>맞게 고른 학생이 틀린다.</b>
     *
     * @param given 문항 번호 → 학생 답. ★ <b>안 보낸 문항은 무응답으로 채점한다</b> —
     *              빠진 문항을 건너뛰면 그 문항이 영원히 채점 전으로 남는다
     */
    @Transactional
    public VocabAttempt submit(Long enrollmentId, Long attemptId, Map<Short, String> given) {
        VocabAttempt attempt = attemptRepository.findById(attemptId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOCAB_ATTEMPT_NOT_FOUND));

        if (!attempt.getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.VOCAB_ATTEMPT_FORBIDDEN);
        }
        if (attempt.isSubmitted()) {
            // ★ 다시 제출하면 점수가 덮인다. 재응시는 start 로 새 건을 만든다
            throw new BusinessException(ErrorCode.VOCAB_ATTEMPT_ALREADY_SUBMITTED);
        }

        var answers = answerRepository
                .findByAttemptIdAndDeletedFalseOrderBySeqNoAsc(attemptId);
        if (answers.isEmpty()) {
            throw new BusinessException(ErrorCode.VOCAB_ANSWER_MISMATCH);
        }

        // ★★ 정답 후보를 단어마다 조회하지 않는다 — 한 번에 읽어 묶는다.
        //   제출은 시험이 끝나는 순간 한꺼번에 몰리는 호출이라 start 보다 더 위험하다
        //   (실측: 문항당 +1 → 문항 20개면 쿼리 20개가 더 난다).
        var wordIds = answers.stream()
                .filter(a -> !a.isPhrase())
                .map(a -> a.getWord().getId())
                .distinct().toList();
        Map<Long, List<String>> candidates = wordIds.isEmpty() ? Map.of() : meaningRepository
                .findByWordIdInAndDeletedFalse(wordIds).stream()
                .collect(Collectors.groupingBy(m -> m.getWord().getId(),
                        Collectors.mapping(VocabMeaning::getText, Collectors.toList())));

        // ★★ Part 3 도 한 번에 읽는다 — 문항마다 getPhrase() 를 풀면 쿼리가 문항 수만큼
        //   늘어난다(제출은 시험이 끝나는 순간 한꺼번에 몰리는 호출이다).
        //   ⚠️ Part 3 에는 vocab_meaning 이 없어 뜻 한 칸을 그 자리에서 쪼갠다 —
        //   관리자 인정 표현은 표제어에만 있다.
        var phraseIds = answers.stream()
                .filter(VocabAnswer::isPhrase)
                .map(a -> a.getPhrase().getId())
                .filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, String> phraseMeanings = phraseIds.isEmpty() ? Map.of()
                : phraseRepository.findAllById(phraseIds).stream()
                        .filter(ph -> ph.getMeaning() != null)
                        .collect(Collectors.toMap(VocabPhrase::getId, VocabPhrase::getMeaning));

        int correct = 0;
        for (VocabAnswer answer : answers) {
            String raw = given.get(answer.getSeqNo());
            // ★ Part 3 는 vocab_meaning 이 없어 뜻 한 칸을 같은 규칙으로 그 자리에서 쪼갠다
            List<String> itemCandidates = answer.isPhrase()
                    ? MeaningSplitter.split(phraseMeanings.get(answer.getPhrase().getId()))
                    : candidates.getOrDefault(answer.getWord().getId(), List.of());
            boolean ok = answer.getType() == VocabAnswer.Type.MC
                    // ★ 객관식 정답은 보기에 넣은 「첫 후보」다 — ChoicePicker 가 그렇게 넣었다
                    ? VocabGrader.isCorrectChoice(raw,
                            itemCandidates.isEmpty() ? "" : itemCandidates.get(0))
                    : VocabGrader.isCorrect(raw, itemCandidates);
            // ★ 빈 답도 그대로 남긴다 — 관리자가 오답을 보고 인정 표현을 넓히는 근거다
            answer.grade(raw, ok);
            if (ok) {
                correct++;
            }
        }

        attempt.submit(Instant.now(clock), correct);
        return attempt;
    }

    /** 결과 조회 — ★ <b>제출한 뒤에만</b> 정오·정답이 보인다(v1 6장). */
    @Transactional(readOnly = true)
    public VocabAttempt result(Long enrollmentId, Long attemptId) {
        VocabAttempt attempt = attemptRepository.findById(attemptId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.VOCAB_ATTEMPT_NOT_FOUND));
        if (!attempt.getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.VOCAB_ATTEMPT_FORBIDDEN);
        }
        return attempt;
    }

    /** 내 기록 (v1 6장). */
    @Transactional(readOnly = true)
    public List<VocabAttempt> history(Long enrollmentId) {
        return attemptRepository
                .findByEnrollmentIdAndDeletedFalseOrderByStartedAtDesc(enrollmentId);
    }

    @Transactional(readOnly = true)
    public List<VocabAnswer> answers(Long attemptId) {
        return answerRepository.findByAttemptIdAndDeletedFalseOrderBySeqNoAsc(attemptId);
    }

    /**
     * 결과 화면에 발음·예문·유의어를 보여줄 것인가 (업체 확정 2026-10-22, 기본 켜짐).
     *
     * <p>★ <b>지점 설정을 서버가 판정한다.</b> 앱이 들고 있으면 끄려고 앱을 배포해야 한다.
     *
     * <p>⚠️ 설정 행이 없으면 보여주지 않는다 — 그 지점은 시험 자체가 돌지 않으므로
     * 여기서 예외를 던질 자리가 아니다.
     */
    @Transactional(readOnly = true)
    public boolean showWordDetail(VocabAttempt attempt) {
        return configRepository
                .findByAcademyIdAndYearAndDeletedFalse(
                        attempt.getRound().getAcademy().getId(), attempt.getYear())
                .map(VocabScheduleConfig::isShowWordDetail)
                .orElse(false);
    }

    private List<Question> questionsOf(VocabAttempt attempt) {
        return answerRepository
                .findByAttemptIdAndDeletedFalseOrderBySeqNoAsc(attempt.getId())
                .stream()
                .map(a -> new Question(a.getSeqNo(),
                        a.isPhrase() ? null : a.getWord().getId(),
                        a.isPhrase() ? a.getPhrase().getId() : null,
                        a.isPhrase() ? ExamItem.Part.PHRASE : ExamItem.Part.WORD,
                        a.prompt(), a.getType(), a.choiceList()))
                .toList();
    }

    /**
     * 회차 범위의 출제 대상 — ★ 출제에서 뺀 단어는 제외한다.
     *
     * <p>★ <b>Part 3 도 범위에 들어온다</b>(업체 확정 2026-10-22) — 유닛당 6문항이
     * 늘어난다. 지점 설정으로 끌 수 있다.
     *
     * <p>⚠️ 조회가 <b>유닛마다</b> 돌고 문항마다 돌지는 않는다. 3회독은 하루 5유닛이라
     * 최대 5(+Part 3 5)번이고, 문항 수(30)와는 무관하다.
     */
    private List<ExamItem> itemsInRange(VocabExamRound round, boolean includePhrase) {
        List<ExamItem> pool = new ArrayList<>();
        for (short unit = round.getUnitFrom(); unit <= round.getUnitTo(); unit++) {
            wordRepository.findByUnitNoAndDeletedFalseOrderBySeqNoAsc(unit).stream()
                    .filter(VocabWord::isActive)
                    .map(ExamItem::of)
                    .forEach(pool::add);
            if (includePhrase) {
                phraseRepository.findByUnitNoAndDeletedFalse(unit).stream()
                        // ⚠️ 뜻이 비면 아무도 맞힐 수 없는 문항이 된다 — 출제하지 않는다
                        .filter(ph -> ph.getMeaning() != null && !ph.getMeaning().isBlank())
                        .map(ExamItem::of)
                        .forEach(pool::add);
            }
        }
        return pool;
    }
}
