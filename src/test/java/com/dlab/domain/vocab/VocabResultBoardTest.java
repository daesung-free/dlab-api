package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.service.VocabExamService;
import com.dlab.domain.vocab.service.VocabResultBoardService;
import com.dlab.domain.vocab.service.VocabWrongAnswerService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 결과 조회 (v1 7장).
 *
 * <h2>★★ 지키려는 것은 「미응시와 0점이 구분된다」다</h2>
 * 이 화면의 목적이 <i>"참여 여부와 점수"</i>라 둘이 섞이면 쓸 수 없다. 미응시를 0 으로
 * 채우면 <b>늦게 입학한 학생이 전부 바닥</b>이 되고, 담당자가 그 학생을 「공부 안 한
 * 학생」으로 읽는다.
 */
@SpringBootTest
@Transactional
class VocabResultBoardTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 2);   // 월요일

    @Autowired VocabResultBoardService boardService;
    @Autowired VocabWrongAnswerService wrongAnswerService;
    @Autowired VocabExamService examService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    ClassMaster myClass;
    StudentEnrollment student;
    StudentEnrollment other;
    VocabExamRound round1;
    VocabExamRound round2;
    AuthPrincipal admin;
    AuthPrincipal homeroomTeacher;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) START.getYear();
        academy = new Academy("VR01", "결과지점", LocalTime.of(9, 0));
        em.persist(academy);

        var config = new VocabScheduleConfig(academy, year, START);
        config.modify(null, (short) 4, (short) 8, (short) 4, (short) 0, false, true);   // 전부 주관식
        em.persist(config);

        for (int i = 1; i <= 8; i++) {
            word((short) 1, i, "word" + i, "뜻" + i);
        }
        for (int i = 1; i <= 8; i++) {
            word((short) 2, 20 + i, "two" + i, "둘뜻" + i);
        }

        round1 = new VocabExamRound(academy, year, 1, (short) 1,
                START, false, (short) 1, (short) 1);
        round2 = new VocabExamRound(academy, year, 2, (short) 1,
                START.plusDays(1), false, (short) 2, (short) 2);
        em.persist(round1);
        em.persist(round2);

        var teacher = new Teacher(academy, "담임쌤", "010-1111-2222");
        em.persist(teacher);
        myClass = new ClassMaster(academy, year, "1반", ClassType.FIXED, teacher);
        em.persist(myClass);

        student = enroll("VR-S1", "응시학생", "VR-0001", myClass);
        other = enroll("VR-S2", "미응시학생", "VR-0002", null);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        var account = Account.forTeacher(teacher, "vr-teacher", "x", false);
        em.persist(account);
        em.flush();
        homeroomTeacher = AuthPrincipal.of(account.getId(), "TEACHER", academy.getId(),
                List.of(Role.TEACHER), false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo, ClassMaster clazz) {
        var person = new Student(code, name, "010-0000-0000");
        em.persist(person);
        var e = new StudentEnrollment(person, academy, year, stdNo, null, GradeType.N_SU);
        em.persist(e);
        if (clazz != null) {
            em.persist(new com.dlab.domain.user.entity.ClassAssignment(
                    academy, e, clazz, ClassType.FIXED));
        }
        return e;
    }

    private void word(short unit, int seq, String text, String meaning) {
        var w = new VocabWord(unit, "%04d".formatted(seq), text, meaning);
        w.overwrite(unit, text, null, "n", meaning, null, null, null, null, null, null, null);
        em.persist(w);
        em.persist(new VocabMeaning(w, meaning, VocabMeaning.Origin.SPLIT));
    }

    /** 회차 하나를 다 맞게 푼다. */
    private void takeAllCorrect(StudentEnrollment e, VocabExamRound round) {
        var started = examService.start(e.getId(), round.getId());
        Map<Short, String> given = new HashMap<>();
        for (var q : started.questions()) {
            var w = em.find(VocabWord.class, q.wordId());
            given.put(q.seqNo(), w.getMeaningRaw());
        }
        examService.submit(e.getId(), started.attemptId(), given);
    }

    /** 회차 하나를 다 틀리게 푼다. */
    private void takeAllWrong(StudentEnrollment e, VocabExamRound round) {
        var started = examService.start(e.getId(), round.getId());
        Map<Short, String> given = new HashMap<>();
        for (var q : started.questions()) {
            given.put(q.seqNo(), "엉뚱한답");
        }
        examService.submit(e.getId(), started.attemptId(), given);
    }

    @Test
    @DisplayName("★★ 미응시는 null 이고 0점은 0 이다 — 섞으면 이 화면이 쓸모없다")
    void distinguishesNotTakenFromZero() {
        takeAllWrong(student, round1);   // 봤고 다 틀렸다
        em.flush();

        var rows = boardService.students(admin, academy.getId(), year, null, null, null);

        var taker = rows.stream().filter(r -> r.enrollmentId().equals(student.getId()))
                .findFirst().orElseThrow();
        var skipper = rows.stream().filter(r -> r.enrollmentId().equals(other.getId()))
                .findFirst().orElseThrow();

        assertThat(taker.averageScore()).as("보고 다 틀렸다 — 가르칠 일이다").isZero();
        assertThat(skipper.averageScore()).as("★ 안 봤다 — 독려할 일이다").isNull();
        assertThat(skipper.takenCount()).isZero();
    }

    @Test
    @DisplayName("★ 참여율은 공개 회차 대비다 — 공개가 없으면 0% 가 아니라 null 이다")
    void participationRate() {
        takeAllCorrect(student, round1);
        em.flush();

        var rows = boardService.students(admin, academy.getId(), year, null, null, null);
        var taker = rows.stream().filter(r -> r.enrollmentId().equals(student.getId()))
                .findFirst().orElseThrow();

        assertThat(taker.openCount()).isEqualTo(2);
        assertThat(taker.takenCount()).isEqualTo(1);
        assertThat(taker.participationRate()).isEqualTo(50);

        // 기간을 공개된 회차 밖으로 잡으면 공개 0건이다
        var empty = boardService.students(admin, academy.getId(), year,
                START.plusYears(1), START.plusYears(1).plusDays(7), null);
        assertThat(empty.get(0).participationRate())
                .as("★ 0% 로 두면 「아무도 안 봤다」로 읽힌다").isNull();
    }

    @Test
    @DisplayName("★★ 재응시는 세지 않는다 — 참여율이 100% 를 넘으면 안 된다")
    void retryDoesNotInflateParticipation() {
        takeAllWrong(student, round1);
        takeAllCorrect(student, round1);   // 재응시
        em.flush();

        var rows = boardService.students(admin, academy.getId(), year, null, null, null);
        var taker = rows.stream().filter(r -> r.enrollmentId().equals(student.getId()))
                .findFirst().orElseThrow();

        assertThat(taker.takenCount()).isEqualTo(1);
        assertThat(taker.participationRate()).isEqualTo(50);
        // ★ 대표는 최초 건이라 0점이다 — 재응시로 점수를 올릴 수 없다(v1 Q5)
        assertThat(taker.averageScore()).isZero();
    }

    @Test
    @DisplayName("★ 반·담임이 채워진다 — 이미 있는 조회를 그대로 쓴다")
    void fillsClassAndHomeroom() {
        var rows = boardService.students(admin, academy.getId(), year, null, null, null);

        var assigned = rows.stream().filter(r -> r.enrollmentId().equals(student.getId()))
                .findFirst().orElseThrow();
        assertThat(assigned.className()).isEqualTo("1반");
        assertThat(assigned.homeroomTeacherName()).isEqualTo("담임쌤");

        // 반 미배정 학생은 담임도 없다 — 담임은 반에 붙어 있다
        var unassigned = rows.stream().filter(r -> r.enrollmentId().equals(other.getId()))
                .findFirst().orElseThrow();
        assertThat(unassigned.className()).isNull();
        assertThat(unassigned.homeroomTeacherName()).isNull();
    }

    @Test
    @DisplayName("★★ 담임은 맡은 학생만 본다 — 출결·루틴과 같은 판정을 쓴다")
    void homeroomTeacherSeesOwnStudentsOnly() {
        var rows = boardService.students(homeroomTeacher, academy.getId(), year,
                null, null, null);

        assertThat(rows).extracting(VocabResultBoardService.StudentRow::enrollmentId)
                .containsExactly(student.getId())
                .doesNotContain(other.getId());
    }

    @Test
    @DisplayName("★ 휴원·퇴원은 목록에서 빠진다 — 참여율 목록이 퇴원생으로 채워진다")
    void excludesNonEnrolled() {
        em.createQuery("update StudentEnrollment e set e.enrollmentStatus = :s where e.id = :id")
                .setParameter("s", EnrollmentStatus.WITHDRAWN)
                .setParameter("id", other.getId())
                .executeUpdate();
        em.flush();
        em.clear();

        var rows = boardService.students(admin, academy.getId(), year, null, null, null);

        assertThat(rows).extracting(VocabResultBoardService.StudentRow::enrollmentId)
                .doesNotContain(other.getId());
    }

    @Test
    @DisplayName("★★ 유닛별에서 금요일 누적은 빠진다 — 어느 유닛 점수인지 가를 수 없다")
    void unitBoardExcludesWeekly() {
        var weekly = new VocabExamRound(academy, year, 3, (short) 1,
                START.plusDays(4), true, (short) 1, (short) 2);
        em.persist(weekly);
        em.flush();

        var units = boardService.units(admin, academy.getId(), year, null, null, null);

        // 평일 회차 2건(1유닛·2유닛)만 센다 — 누적을 넣으면 두 유닛이 같은 값으로 물든다
        assertThat(units).extracting(VocabResultBoardService.UnitRow::unitNo)
                .containsExactly((short) 1, (short) 2);
        assertThat(units).allSatisfy(u -> assertThat(u.roundCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("★ 유닛별 정답률 — 응시가 없으면 null 이다")
    void unitAverage() {
        takeAllCorrect(student, round1);
        em.flush();

        var units = boardService.units(admin, academy.getId(), year, null, null, null);

        assertThat(units.get(0).averageScore()).isEqualTo(100);
        assertThat(units.get(1).averageScore()).as("2유닛은 아무도 안 봤다").isNull();
    }

    @Test
    @DisplayName("★ 회차별 — 대상 수와 응시 수가 따로 나온다")
    void roundBoard() {
        takeAllCorrect(student, round1);
        em.flush();

        var rounds = boardService.rounds(admin, academy.getId(), year, null, null, null);

        var first = rounds.get(0);
        assertThat(first.targetCount()).isEqualTo(2);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.averageScore()).isEqualTo(100);
        assertThat(rounds.get(1).averageScore()).isNull();
    }

    @Test
    @DisplayName("★★ 오답 확인이 같은 답끼리 묶는다 — 묶지 않으면 200줄을 읽어야 한다")
    void wrongAnswersAreGrouped() {
        // ⚠️ 주관식만 나오게 고정한다. ★ 객관식·주관식 배정이 무작위라, 같은 단어가
        //    한 학생에게는 주관식 다른 학생에게는 객관식으로 나올 수 있다. givens 는
        //    객관식 오답을 제외하므로(바로 아래 테스트가 그것을 단정한다) 그러면
        //    words.get(0) 의 count 가 1 이 되어 **CI 에서만 깨진다.**
        //    실제로 그렇게 깨졌다(2026-10-23). 묶는지 보는 테스트이지 유형을 보는
        //    테스트가 아니므로 유형을 고정하는 것이 맞다.
        em.createQuery("update VocabScheduleConfig c set c.multipleChoiceRate = 0 "
                        + "where c.academy.id = :a")
                .setParameter("a", academy.getId())
                .executeUpdate();
        em.flush();
        em.clear();

        // 두 학생이 같은 틀린 답을 쓴다
        takeAllWrong(student, round1);
        takeAllWrong(other, round1);
        em.flush();

        var words = wrongAnswerService.words(admin, (short) 1);
        assertThat(words).isNotEmpty();

        var givens = wrongAnswerService.givens(admin, words.get(0).wordId());
        assertThat(givens).hasSize(1);
        assertThat(givens.get(0).given()).isEqualTo("엉뚱한답");
        assertThat(givens.get(0).count())
                .as("★ 같은 표현을 여럿이 썼으면 인정해야 할 표현이다").isEqualTo(2);
    }

    @Test
    @DisplayName("★ 객관식 오답은 목록에 안 들어온다 — 보기 중 하나라 인정 표현이 될 수 없다")
    void wrongAnswersExcludeMultipleChoice() {
        // 객관식만 나오게 설정을 바꾼다
        em.createQuery("update VocabScheduleConfig c set c.multipleChoiceRate = 100 "
                        + "where c.academy.id = :a")
                .setParameter("a", academy.getId())
                .executeUpdate();
        em.flush();
        em.clear();

        takeAllWrong(student, round1);
        em.flush();

        assertThat(wrongAnswerService.words(admin, (short) 1))
                .as("주관식이 없으니 오답 목록도 비어 있다").isEmpty();
    }

    @Test
    @DisplayName("★ 아직 아무도 안 푼 단어는 오답 목록에 없다")
    void untouchedWordsAreNotListed() {
        assertThat(wrongAnswerService.words(admin, (short) 1)).isEmpty();
    }
}
