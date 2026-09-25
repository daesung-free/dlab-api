package com.dlab.api.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.entity.StudentGradeSubmission;
import com.dlab.domain.grade.service.ExamFormService;
import com.dlab.domain.grade.service.StudentGradeService;
import com.dlab.domain.grade.service.StudentGradeService.ScoreInput;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 성적 입력 (앱 A-2).
 *
 * <p>양식이 학년별 마스터 데이터라는 것이 이 도메인의 핵심이다 — 예비고3은 학력평가에
 * 통합사회·통합과학, N수·현고3은 평가원+수능에 탐구1·탐구2로 구성이 통째로 다르다.
 * 여기서 확인하는 것은 <b>양식 밖 값이 저장되지 않는다</b>는 것과
 * <b>"모른다"가 0과 구분된다</b>는 것 두 가지다.
 */
@SpringBootTest
@Transactional
class StudentGradeTest {

    @Autowired StudentGradeService gradeService;
    @Autowired ExamFormService examFormService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2099;

    Academy bundang;
    StudentEnrollment minji;    // 현 고3 → 평가원 + 수능
    StudentEnrollment jiwon;    // 현 고2 → 학력평가

    ExamMaster june;            // 고3 6월 평가원
    ExamMaster csat;            // 고3 수능 (한국사 포함)
    ExamSubject korean;
    ExamSubject math;
    ExamSubject history;        // 절대평가 — 등급만
    ExamMaster high2June;
    ExamSubject high2Korean;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        minji = enrollment("김민지", "2099-0001", GradeType.HIGH3);
        jiwon = enrollment("박지원", "2099-0002", GradeType.HIGH2);

        june = ExamMaster.common(YEAR, GradeType.HIGH3, ExamCode.JUNE, "6월 평가원 모의고사", 1);
        korean = june.addSubject("KOREAN", "국어", 1);
        math = june.addSubject("MATH", "수학", 2);
        em.persist(june);

        csat = ExamMaster.common(YEAR, GradeType.HIGH3, ExamCode.CSAT, "2099학년도 수능", 2);
        csat.addSubject("KOREAN", "국어", 1);
        // 한국사는 절대평가라 등급만 받는다
        history = csat.addSubject("HISTORY", "한국사", 9, false, false, true);
        em.persist(csat);

        high2June = ExamMaster.common(YEAR, GradeType.HIGH2, ExamCode.JUNE, "2099년 6월 학력평가", 1);
        high2Korean = high2June.addSubject("KOREAN", "국어", 1);
        high2June.addSubject("SOCIAL", "통합사회", 4);
        em.persist(high2June);
        em.flush();
    }

    private StudentEnrollment enrollment(String name, String studentNo, GradeType grade) {
        Student student = new Student("DL-" + studentNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(student, bundang, YEAR, studentNo, null, grade);
        em.persist(e);
        return e;
    }

    // ─────────────────────────────────────────── 양식

    @Test
    @DisplayName("양식은 학년마다 다르다 — 고3은 평가원+수능, 고2는 학력평가")
    void formDiffersByGrade() {
        List<ExamFormService.Form> high3 = examFormService.formOf(minji);
        List<ExamFormService.Form> high2 = examFormService.formOf(jiwon);

        assertThat(high3).hasSize(2);
        assertThat(high3.get(0).exam().getExamName()).isEqualTo("6월 평가원 모의고사");
        assertThat(high2).hasSize(1);
        assertThat(high2.get(0).exam().getExamName()).isEqualTo("2099년 6월 학력평가");
    }

    @Test
    @DisplayName("양식이 없으면 빈 목록이 아니라 오류다 — 마스터 미투입과 '낼 게 없음'을 구분해야 한다")
    void missingFormFails() {
        StudentEnrollment nsu = enrollment("최n수", "2099-0003", GradeType.N_SU);
        em.flush();

        assertThatThrownBy(() -> examFormService.formOf(nsu))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXAM_FORM_NOT_FOUND);
    }

    // ─────────────────────────────────────────── 내신

    @Test
    @DisplayName("내신은 주요교과평균 한 칸이다")
    void schoolRecordIsSingleValue() {
        StudentGradeSubmission saved =
                gradeService.saveSchoolRecord(minji, new BigDecimal("2.35"));

        assertThat(saved.getMainSubjectAverage()).isEqualByComparingTo("2.35");
        assertThat(saved.getSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("성적을 아직 안 낸 학생도 조회하면 빈 제출이 나온다 — 앱이 분기하지 않게")
    void mineCreatesEmptySubmission() {
        StudentGradeSubmission submission = gradeService.mine(minji);

        assertThat(submission.isEmpty()).isTrue();
        assertThat(submission.getMainSubjectAverage()).isNull();
    }

    // ─────────────────────────────────────────── 모의고사

    @Test
    @DisplayName("모의고사 성적이 과목별로 저장된다")
    void savesExamScores() {
        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2),
                new ScoreInput(math.getId(), (short) 140, (short) 98, (short) 1)));

        assertThat(saved.activeScores()).hasSize(2);
        assertThat(saved.activeScores())
                .extracting(s -> s.getExamSubject().getSubjectName(), s -> s.getStandardScore())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("국어", (short) 132),
                        org.assertj.core.groups.Tuple.tuple("수학", (short) 140));
    }

    @Test
    @DisplayName("★ 같은 회차를 다시 내면 통째로 교체된다 — 지운 칸이 남으면 안 된다")
    void resubmitReplacesExam() {
        gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2),
                new ScoreInput(math.getId(), (short) 140, (short) 98, (short) 1)));
        em.flush();

        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 128, (short) 90, (short) 3)));
        // ★ 커밋 시점과 같게 반영까지 본다. 이게 없어서 재제출이 유니크 제약에 걸리는 것을
        //   이 테스트가 잡지 못했다
        em.flush();

        assertThat(saved.activeScores()).hasSize(1);
        assertThat(saved.activeScores().get(0).getStandardScore()).isEqualTo((short) 128);
    }

    @Test
    @DisplayName("★ 보낸 회차만 교체된다 — 6월만 다시 내도 수능 성적은 남는다")
    void resubmitDoesNotTouchOtherExams() {
        ExamSubject csatKorean = csat.activeSubjects().get(0);
        gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2),
                new ScoreInput(csatKorean.getId(), (short) 125, (short) 88, (short) 3)));
        em.flush();

        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 100, (short) 60, (short) 5)));

        assertThat(saved.activeScores()).hasSize(2);
        assertThat(saved.activeScores())
                .filteredOn(s -> s.getExamMaster().getId().equals(csat.getId()))
                .singleElement()
                .satisfies(s -> assertThat(s.getStandardScore()).isEqualTo((short) 125));
    }

    @Test
    @DisplayName("★ 절대평가 과목의 표준점수·백분위는 버린다 — 평균이 조용히 오염된다")
    void dropsFieldsNotInForm() {
        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(history.getId(), (short) 130, (short) 95, (short) 1)));

        assertThat(saved.activeScores()).singleElement().satisfies(score -> {
            assertThat(score.getStandardScore()).isNull();
            assertThat(score.getPercentile()).isNull();
            assertThat(score.getGradeLevel()).isEqualTo((short) 1);
        });
    }

    @Test
    @DisplayName("★ 절대평가 과목에 없는 칸만 보내면 아무것도 저장되지 않는다")
    void dropsScoreWithOnlyInvalidFields() {
        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(history.getId(), (short) 130, (short) 95, null)));

        assertThat(saved.activeScores()).isEmpty();
    }

    @Test
    @DisplayName("빈 줄은 저장하지 않는다 — 미입력과 구분되지 않는 행이 남는다")
    void skipsBlankInput() {
        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), null, null, null),
                new ScoreInput(math.getId(), (short) 140, (short) 98, (short) 1)));

        assertThat(saved.activeScores()).singleElement()
                .satisfies(s -> assertThat(s.getExamSubject().getSubjectName()).isEqualTo("수학"));
    }

    @Test
    @DisplayName("★ 남의 학년 과목 ID를 실어 보내면 거절한다 — 학년 통계가 섞인다")
    void rejectsSubjectFromAnotherGradeForm() {
        assertThatThrownBy(() -> gradeService.saveExamScores(minji, List.of(
                new ScoreInput(high2Korean.getId(), (short) 130, (short) 95, (short) 1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXAM_SUBJECT_NOT_IN_FORM);
    }

    // ─────────────────────────────────────────── 건너뛰기

    @Test
    @DisplayName("★ '모른다'는 0이 아니다 — 사유가 남고 점수는 비어 있다")
    void skipIsNotZero() {
        StudentGradeSubmission saved = gradeService.skipExams(minji, "검정고시 출신이라 모의고사 응시 이력 없음");

        assertThat(saved.isExamSkipped()).isTrue();
        assertThat(saved.getSkipReason()).contains("검정고시");
        assertThat(saved.activeScores()).isEmpty();
    }

    @Test
    @DisplayName("사유 없이는 건너뛸 수 없다")
    void skipRequiresReason() {
        assertThatThrownBy(() -> gradeService.skipExams(minji, "  "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GRADE_SKIP_REASON_REQUIRED);
    }

    @Test
    @DisplayName("★ 건너뛴 뒤 점수를 넣으면 기존 점수가 남지 않는다")
    void skipClearsExistingScores() {
        gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2)));
        em.flush();

        StudentGradeSubmission saved = gradeService.skipExams(minji, "성적표 분실");

        assertThat(saved.activeScores()).isEmpty();
        assertThat(saved.isExamSkipped()).isTrue();
    }

    @Test
    @DisplayName("★ 다시 점수를 넣으면 '모른다'가 풀린다 — 안 풀면 점수가 있는데 미제출로 집계된다")
    void savingScoresUnskips() {
        gradeService.skipExams(minji, "성적표 분실");
        em.flush();

        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2)));

        assertThat(saved.isExamSkipped()).isFalse();
        assertThat(saved.getSkipReason()).isNull();
        assertThat(saved.activeScores()).hasSize(1);
    }

    @Test
    @DisplayName("내신과 모의고사는 서로를 지우지 않는다")
    void schoolRecordAndExamsCoexist() {
        gradeService.saveSchoolRecord(minji, new BigDecimal("1.80"));
        StudentGradeSubmission saved = gradeService.saveExamScores(minji, List.of(
                new ScoreInput(korean.getId(), (short) 132, (short) 94, (short) 2)));

        assertThat(saved.getMainSubjectAverage()).isEqualByComparingTo("1.80");
        assertThat(saved.activeScores()).hasSize(1);
    }
}
