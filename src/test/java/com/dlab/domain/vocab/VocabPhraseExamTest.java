package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.vocab.entity.VocabAnswer;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabPhrase;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.service.ExamItem;
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
 * Part 3(구동사·숙어·혼동어휘) 출제 (업체 확정 2026-10-22).
 *
 * <h2>★ 회신 2번 — Part 3 를 시험 범위에 넣는다</h2>
 * 표제어와 <b>같은 방식</b>(뜻 맞히기)으로 내고 <b>유닛당 6문항이 늘어난다.</b>
 * Part 2(예문 빈칸)는 넣지 않는다.
 *
 * <h2>★★ 표제어 테이블에 끼워 넣지 않았다</h2>
 * {@code vocab_word} 는 번호 {@code 0001}~{@code 2000} 체계이고 원고 재업로드가 그
 * 번호로 덮어쓴다 — 구동사를 섞으면 그 구조가 무너진다. 그래서 응시 기록
 * ({@code vocab_answer})이 <b>표제어와 Part 3 중 하나</b>를 가리킨다.
 */
@SpringBootTest
@Transactional
class VocabPhraseExamTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 1);

    @Autowired VocabExamService examService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    VocabScheduleConfig config;
    VocabExamRound round;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) START.getYear();
        academy = new Academy("VP01", "Part3지점", LocalTime.of(9, 0));
        em.persist(academy);

        config = new VocabScheduleConfig(academy, year, START);
        em.persist(config);

        // 표제어 40 + Part 3 6 — 실제 유닛 구성 그대로다
        for (int i = 1; i <= 40; i++) {
            word((short) 1, i, "word" + i, "뜻" + i);
        }
        for (int i = 1; i <= 6; i++) {
            em.persist(new VocabPhrase((short) 1, VocabPhrase.Kind.PHRASAL_VERB,
                    "take " + i, "구동사뜻" + i, "He took " + i + ".", "그는 " + i + "을 했다."));
        }

        round = new VocabExamRound(academy, year, 1, (short) 1,
                START, false, (short) 1, (short) 1);
        em.persist(round);

        var person = new Student("VP-STD", "구동사학생", "010-0000-0001");
        em.persist(person);
        enrollment = new StudentEnrollment(person, academy, year,
                "VP-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();
    }

    private void word(short unit, int seq, String text, String meaning) {
        var w = new VocabWord(unit, "%04d".formatted(seq), text, meaning);
        w.overwrite(unit, text, "[wɜːrd]", "n", meaning, "syn", "ant",
                "This is " + text + ".", "이것은 " + text + "이다.", "24 수능", "파생", null);
        em.persist(w);
        em.persist(new VocabMeaning(w, meaning, VocabMeaning.Origin.SPLIT));
    }

    @Test
    @DisplayName("★★ 문항 수는 30이다 — 업체 확정. 46개 풀에서 30개를 뽑는다")
    void thirtyQuestions() {
        var started = examService.start(enrollment.getId(), round.getId());

        assertThat(started.questions()).hasSize(30);
    }

    @Test
    @DisplayName("★★ Part 3 가 출제된다 — 표제어 40 + 구동사 6 이 한 풀이다")
    void phrasesAreIncluded() {
        // 표제어를 전부 빼서 Part 3 만 남긴다 — 무작위 추출이라 이렇게 해야 단정할 수 있다
        em.createQuery("update VocabWord w set w.active = false where w.unitNo = 1")
                .executeUpdate();
        em.flush();
        em.clear();

        var started = examService.start(enrollment.getId(), round.getId());

        assertThat(started.questions()).hasSize(6);
        assertThat(started.questions())
                .allMatch(q -> q.part() == ExamItem.Part.PHRASE)
                .allMatch(q -> q.wordId() == null && q.phraseId() != null)
                // ★ 문제는 구동사 표현이다
                .allMatch(q -> q.word().startsWith("take "));
    }

    @Test
    @DisplayName("★ 지점 설정으로 Part 3 를 끌 수 있다 — 끄면 표제어만 나온다")
    void canTurnPhrasesOff() {
        config.modify(null, null, null, null, null, false, null);
        em.createQuery("update VocabWord w set w.active = false where w.seqNo <> '0001'")
                .executeUpdate();
        em.flush();
        em.clear();

        var started = examService.start(enrollment.getId(), round.getId());

        assertThat(started.questions()).hasSize(1);
        assertThat(started.questions().get(0).part()).isEqualTo(ExamItem.Part.WORD);
    }

    @Test
    @DisplayName("★★ Part 3 도 같은 채점 규칙을 쓴다 — 뜻 한 칸을 쪼개 비교한다")
    void phraseIsGradedWithTheSameRule() {
        config.modify(null, null, null, null, (short) 0, null, null);   // 전부 주관식
        em.createQuery("update VocabWord w set w.active = false where w.unitNo = 1")
                .executeUpdate();
        // 뜻이 여러 개인 구동사를 하나 둔다 — 「다루다」만 써도 맞아야 한다
        em.persist(new VocabPhrase((short) 1, VocabPhrase.Kind.PHRASAL_VERB,
                "deal with", "~을 처리하다, 다루다", null, null));
        em.flush();
        em.clear();

        var started = examService.start(enrollment.getId(), round.getId());
        var target = started.questions().stream()
                .filter(q -> "deal with".equals(q.word()))
                .findFirst();
        assertThat(target).as("7개 전부 출제된다(상한 30)").isPresent();

        Map<Short, String> given = new HashMap<>();
        given.put(target.get().seqNo(), "다루다");
        examService.submit(enrollment.getId(), started.attemptId(), given);

        var graded = examService.answers(started.attemptId()).stream()
                .filter(a -> a.getSeqNo().equals(target.get().seqNo()))
                .findFirst().orElseThrow();
        assertThat(graded.getCorrect())
                .as("★ 「~을 처리하다, 다루다」의 두 번째 뜻이다").isTrue();
        assertThat(graded.isPhrase()).isTrue();
        assertThat(graded.getWord()).as("표제어 쪽은 비어 있다").isNull();
    }

    @Test
    @DisplayName("★ Part 3 객관식 보기는 Part 3 에서만 뽑는다 — 섞으면 길이만 봐도 답이 보인다")
    void phraseChoicesComeFromPhrasesOnly() {
        config.modify(null, (short) 6, null, null, (short) 100, null, null);
        em.createQuery("update VocabWord w set w.active = false where w.unitNo = 1")
                .executeUpdate();
        em.flush();
        em.clear();

        var started = examService.start(enrollment.getId(), round.getId());

        assertThat(started.questions()).allSatisfy(q -> {
            assertThat(q.type()).isEqualTo(VocabAnswer.Type.MC);
            assertThat(q.choices()).hasSize(4);
            assertThat(q.choices()).allMatch(c -> c.startsWith("구동사뜻"));
        });
    }

    @Test
    @DisplayName("★★ 결과에 발음·예문·유의어가 내려간다 — 업체 확정. 끄면 null 이다")
    void resultCarriesWordDetail() {
        var started = examService.start(enrollment.getId(), round.getId());
        var attempt = examService.submit(enrollment.getId(), started.attemptId(), Map.of());

        var shown = com.dlab.api.app.vocab.AppVocabResponse.Result.of(
                attempt, examService.answers(started.attemptId()), true);
        var wordItem = shown.items().stream()
                .filter(i -> i.part() == ExamItem.Part.WORD)
                .findFirst().orElseThrow();
        assertThat(wordItem.pronunciation()).isEqualTo("[wɜːrd]");
        assertThat(wordItem.exampleEn()).isNotBlank();
        assertThat(wordItem.exampleKo()).isNotBlank();
        assertThat(wordItem.synonym()).isEqualTo("syn");
        assertThat(wordItem.derivative()).isEqualTo("파생");

        var hidden = com.dlab.api.app.vocab.AppVocabResponse.Result.of(
                attempt, examService.answers(started.attemptId()), false);
        assertThat(hidden.items())
                .as("★ 노출을 끄면 데이터는 남고 응답만 비는 것이다")
                .allMatch(i -> i.pronunciation() == null && i.exampleEn() == null
                        && i.synonym() == null)
                // 뜻(정답)은 끄는 대상이 아니다 — 그게 결과 화면의 본문이다
                .allMatch(i -> i.answer() != null);
    }

    @Test
    @DisplayName("★ 노출 설정은 서버가 판정한다 — 앱이 들고 있으면 끄려고 앱을 배포해야 한다")
    void detailToggleComesFromBranchConfig() {
        var started = examService.start(enrollment.getId(), round.getId());
        var attempt = examService.result(enrollment.getId(), started.attemptId());

        assertThat(examService.showWordDetail(attempt)).as("기본은 켜짐이다").isTrue();

        config.modify(null, null, null, null, null, null, false);
        em.flush();
        em.clear();

        assertThat(examService.showWordDetail(
                examService.result(enrollment.getId(), started.attemptId()))).isFalse();
    }
}
