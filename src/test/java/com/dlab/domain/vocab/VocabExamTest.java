package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.service.VocabExamService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 응시·채점 (v1 4장 · 6장).
 *
 * <h2>★★ 가장 중요한 것은 「정답이 나가지 않는가」다</h2>
 * v1 9장이 <i>"정답은 서버 밖으로 나가지 않는다"</i>고 못 박았다. 그건 <b>화면을 봐서는
 * 확인할 수 없는 성질</b>이라 테스트로 고정한다 — 나중에 누가 응답에 뜻을 하나 더 실으면
 * 아무도 모른 채 샌다.
 */
@SpringBootTest
@Transactional
class VocabExamTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 2);   // 월요일

    @Autowired VocabExamService examService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    VocabExamRound round;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) START.getYear();
        academy = new Academy("VX01", "단어시험지점", LocalTime.of(9, 0));
        em.persist(academy);

        var config = new VocabScheduleConfig(academy, year, START);
        // 문항 5개 · 보기 4개 · 객관식 40%(2문항) — 경계를 손으로 만든다
        config.modify(null, (short) 5, (short) 10, (short) 4, (short) 40, false, true);
        em.persist(config);

        // 1유닛에 단어 8개. 보기 4개를 같은 유닛에서 채울 수 있는 수다
        for (int i = 1; i <= 8; i++) {
            word((short) 1, i, "word" + i, "뜻" + i);
        }

        round = new VocabExamRound(academy, year, 1, (short) 1,
                START, false, (short) 1, (short) 1);
        em.persist(round);

        var person = new Student("VX-STD", "단어학생", "010-0000-0001");
        em.persist(person);
        enrollment = new StudentEnrollment(person, academy, year,
                "VX-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();
    }

    private VocabWord word(short unit, int seq, String text, String meaning) {
        var w = new VocabWord(unit, "%04d".formatted(seq), text, meaning);
        w.overwrite(unit, text, null, "n", meaning, null, null, null, null, null, null, null);
        em.persist(w);
        // 정답 후보 — 적재가 하는 일을 손으로 한다
        em.persist(new VocabMeaning(w, meaning, VocabMeaning.Origin.SPLIT));
        return w;
    }

    @Test
    @DisplayName("★★ 응시 시작 응답에 정답이 없다 — v1 9장 「정답은 서버 밖으로 나가지 않는다」")
    void startNeverLeaksTheAnswer() {
        var result = examService.start(enrollment.getId(), round.getId());

        assertThat(result.questions()).hasSize(5);
        for (var q : result.questions()) {
            if (q.type() == VocabAnswer.Type.MC) {
                // 보기는 내려가지만 ★ 어느 것이 정답인지는 내려가지 않는다
                assertThat(q.choices()).hasSize(4);
            } else {
                assertThat(q.choices()).isEmpty();
            }
        }
        // Question 레코드 자체에 정답 칸이 없다 — 실수로 실을 자리가 없게 해 둔다
        assertThat(VocabExamService.Question.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("answer", "correct", "answerIndex", "meaning");
    }

    @Test
    @DisplayName("★★ 제출 전 결과 조회에는 문항별 정오·정답이 비어 있다 — 시작만 하고 답을 보면 안 된다")
    void resultBeforeSubmitHasNoItems() {
        var started = examService.start(enrollment.getId(), round.getId());
        var attempt = examService.result(enrollment.getId(), started.attemptId());

        assertThat(attempt.isSubmitted()).isFalse();
        assertThat(attempt.getScore()).as("★ null 이다 — 0 이면 「다 틀렸다」와 구분되지 않는다")
                .isNull();
        var view = com.dlab.api.app.vocab.AppVocabResponse.Result.of(
                attempt, examService.answers(started.attemptId()));
        assertThat(view.items()).isEmpty();
    }

    @Test
    @DisplayName("★ 제출하면 채점되고 정답이 보인다")
    void submitGradesAndRevealsAnswers() {
        var started = examService.start(enrollment.getId(), round.getId());
        Map<Short, String> given = new HashMap<>();
        for (var q : started.questions()) {
            // 단어 뒤 숫자로 정답을 안다 — word3 이면 「뜻3」
            given.put(q.seqNo(), "뜻" + q.word().replace("word", ""));
        }

        var attempt = examService.submit(enrollment.getId(), started.attemptId(), given);

        assertThat(attempt.getScore()).isEqualTo((short) 5);
        var view = com.dlab.api.app.vocab.AppVocabResponse.Result.of(
                attempt, examService.answers(started.attemptId()));
        assertThat(view.items()).hasSize(5);
        assertThat(view.items()).allMatch(i -> Boolean.TRUE.equals(i.correct()));
    }

    @Test
    @DisplayName("★★ 안 보낸 문항은 무응답으로 채점한다 — 건너뛰면 영원히 채점 전으로 남는다")
    void missingAnswersAreGradedAsBlank() {
        var started = examService.start(enrollment.getId(), round.getId());

        var attempt = examService.submit(enrollment.getId(), started.attemptId(), Map.of());

        assertThat(attempt.getScore()).isEqualTo((short) 0);
        assertThat(examService.answers(started.attemptId()))
                .as("채점 전(null)으로 남은 문항이 없다")
                .allMatch(a -> a.getCorrect() != null);
    }

    @Test
    @DisplayName("★★ 빈 답도 저장한다 — 관리자가 오답을 보고 인정 표현을 넓히는 근거다")
    void blankAnswerIsStored() {
        var started = examService.start(enrollment.getId(), round.getId());
        Map<Short, String> given = new HashMap<>();
        given.put(started.questions().get(0).seqNo(), "");

        examService.submit(enrollment.getId(), started.attemptId(), given);

        var answers = examService.answers(started.attemptId());
        assertThat(answers.get(0).getGiven()).isNotNull();
        assertThat(answers.get(0).getCorrect()).isFalse();
    }

    @Test
    @DisplayName("★★ 진행 중인 응시가 있으면 그것을 돌려준다 — 새로 만들면 응시가 쌓인다")
    void reusesInProgressAttempt() {
        var first = examService.start(enrollment.getId(), round.getId());
        var second = examService.start(enrollment.getId(), round.getId());

        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        // ★ 문항도 같다 — 다시 뽑으면 학생이 보던 문제가 바뀐다
        assertThat(second.questions()).extracting(VocabExamService.Question::word)
                .isEqualTo(first.questions().stream()
                        .map(VocabExamService.Question::word).toList());
    }

    @Test
    @DisplayName("★★ 제출한 건을 다시 제출하면 거절한다 — 점수가 덮인다")
    void cannotSubmitTwice() {
        var started = examService.start(enrollment.getId(), round.getId());
        examService.submit(enrollment.getId(), started.attemptId(), Map.of());

        assertThatThrownBy(() ->
                examService.submit(enrollment.getId(), started.attemptId(), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 제출");
    }

    @Test
    @DisplayName("★ 제출 뒤 다시 시작하면 재응시 건이 새로 생긴다 — 대표 점수는 최초다(v1 Q5)")
    void retryCreatesNewAttempt() {
        var first = examService.start(enrollment.getId(), round.getId());
        Map<Short, String> allWrong = new HashMap<>();
        for (var q : first.questions()) {
            allWrong.put(q.seqNo(), "틀린답");
        }
        examService.submit(enrollment.getId(), first.attemptId(), allWrong);

        var second = examService.start(enrollment.getId(), round.getId());
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());

        Map<Short, String> allRight = new HashMap<>();
        for (var q : second.questions()) {
            allRight.put(q.seqNo(), "뜻" + q.word().replace("word", ""));
        }
        examService.submit(enrollment.getId(), second.attemptId(), allRight);

        // ★ 회차 목록의 점수는 최초 건(0점)이다 — 최고점이면 반복 응시로 올릴 수 있다
        var view = examService.rounds(enrollment.getId()).stream()
                .filter(r -> r.round().getId().equals(round.getId()))
                .findFirst().orElseThrow();
        assertThat(view.representative().getScore())
                .as("최초 제출 건의 점수다").isEqualTo((short) 0);
        // ⚠️ 기록은 둘 다 남는다(v1 6장)
        assertThat(examService.history(enrollment.getId())).hasSize(2);
    }

    @Test
    @DisplayName("★★ 남의 지점 회차는 응시할 수 없다 — id 만 바꿔 들어올 수 있다")
    void cannotAttemptOtherAcademyRound() {
        var other = new Academy("VX02", "다른지점", LocalTime.of(9, 0));
        em.persist(other);
        var otherRound = new VocabExamRound(other, year, 1, (short) 1,
                START, false, (short) 1, (short) 1);
        em.persist(otherRound);
        em.flush();

        assertThatThrownBy(() ->
                examService.start(enrollment.getId(), otherRound.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("응시할 수 없는");
    }

    @Test
    @DisplayName("★★ 남의 응시는 제출·조회할 수 없다 — 점수가 섞인다")
    void cannotTouchOthersAttempt() {
        var started = examService.start(enrollment.getId(), round.getId());

        var person = new Student("VX-STD2", "다른학생", "010-0000-0002");
        em.persist(person);
        var other = new StudentEnrollment(person, academy, year,
                "VX-0002", null, GradeType.N_SU);
        em.persist(other);
        em.flush();

        assertThatThrownBy(() ->
                examService.submit(other.getId(), started.attemptId(), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본인의 응시가 아닙니다");
        assertThatThrownBy(() -> examService.result(other.getId(), started.attemptId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 범위의 단어 수보다 설정값이 크면 단어 수로 줄어든다 — 같은 단어를 두 번 내지 않는다")
    void questionCountIsCappedByPool() {
        // 2유닛에 단어 3개뿐인데 문항 설정은 5다
        for (int i = 1; i <= 3; i++) {
            word((short) 2, 100 + i, "small" + i, "작은뜻" + i);
        }
        var small = new VocabExamRound(academy, year, 2, (short) 1,
                START.plusDays(1), false, (short) 2, (short) 2);
        em.persist(small);
        em.flush();

        var result = examService.start(enrollment.getId(), small.getId());

        assertThat(result.questions()).hasSize(3);
        assertThat(result.questions()).extracting(VocabExamService.Question::wordId)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("★ 출제할 단어가 없으면 거절한다 — 0문항이면 제출만 해도 만점이다")
    void rejectsEmptyRange() {
        var empty = new VocabExamRound(academy, year, 3, (short) 1,
                START.plusDays(2), false, (short) 40, (short) 40);
        em.persist(empty);
        em.flush();

        assertThatThrownBy(() -> examService.start(enrollment.getId(), empty.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("출제할 단어가 없습니다");
    }

    @Test
    @DisplayName("★ 출제에서 뺀 단어는 나오지 않는다")
    void excludesInactiveWords() {
        em.createQuery("update VocabWord w set w.active = false where w.unitNo = 1")
                .executeUpdate();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> examService.start(enrollment.getId(), round.getId()))
                .as("전부 뺐으면 출제할 단어가 없다")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 관리자 인정 표현으로도 정답이 된다 — 그게 오답 확인의 목적이다")
    void acceptsManualMeaning() {
        var started = examService.start(enrollment.getId(), round.getId());
        var first = started.questions().stream()
                .filter(q -> q.type() == VocabAnswer.Type.SA)
                .findFirst().orElseThrow();
        var word = em.find(VocabWord.class, first.wordId());
        em.persist(new VocabMeaning(word, "인정표현", VocabMeaning.Origin.MANUAL));
        em.flush();

        examService.submit(enrollment.getId(), started.attemptId(),
                Map.of(first.seqNo(), "인정표현"));

        var graded = examService.answers(started.attemptId()).stream()
                .filter(a -> a.getSeqNo().equals(first.seqNo()))
                .findFirst().orElseThrow();
        assertThat(graded.getCorrect()).isTrue();
    }

    @Test
    @DisplayName("★★ 입학 전 회차는 구분해서 내린다 — 안 하면 앱이 「밀린 시험」으로 보여 준다")
    void marksRoundsBeforeEnrollment() {
        // 학생이 3/10 입학이고 회차는 3/2 공개다
        em.createQuery("update StudentEnrollment e set e.admissionDate = :d where e.id = :id")
                .setParameter("d", START.plusDays(8))
                .setParameter("id", enrollment.getId())
                .executeUpdate();
        em.flush();
        em.clear();

        var views = examService.rounds(enrollment.getId());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).beforeEnrollment())
                .as("응시는 선택이다(v1 5.5)").isTrue();
    }

    @Test
    @DisplayName("★ 보기 스냅샷이 저장된다 — 단어가 바뀌어도 「그때 무엇을 보고 골랐는가」가 남는다")
    void choicesAreSnapshotted() {
        var started = examService.start(enrollment.getId(), round.getId());

        var mc = examService.answers(started.attemptId()).stream()
                .filter(a -> a.getType() == VocabAnswer.Type.MC)
                .findFirst().orElseThrow();
        assertThat(mc.choiceList()).hasSize(4);
    }
}
