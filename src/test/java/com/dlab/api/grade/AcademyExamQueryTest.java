package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamUniversityChoice;
import com.dlab.domain.grade.service.AcademyExamQueryService;
import com.dlab.domain.grade.service.StudentGradeService;
import com.dlab.domain.grade.service.StudentGradeService.ScoreInput;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 앱 성적 탭 — 디랩에서 본 시험 조회.
 *
 * <p>지키려는 것 — <b>입학 전 성적이 섞이지 않을 것</b>, <b>없는 값을 0 으로 채우지 않을 것</b>,
 * <b>진단이 "안 적어서" 빈 것과 "원래 없어서" 빈 것을 구분할 것</b>.
 */
@SpringBootTest
@Transactional
class AcademyExamQueryTest {

    @Autowired AcademyExamQueryService queryService;
    @Autowired StudentGradeService gradeService;
    @Autowired EntityManager em;
    @Autowired com.dlab.api.admin.grade.AdminGradeController adminController;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2090;

    StudentEnrollment student;
    Academy bundang;
    ExamMaster june;     // 평가원 — 진단 없음
    ExamMaster august;   // 더프 — 진단 있음
    ExamMaster admission;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student s = new Student("DL-Q1", "조회학생", "010-1111-3333");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, YEAR, "2090-0001", null, GradeType.N_SU);
        em.persist(student);

        june = ExamMaster.academyExam(null, YEAR, GradeType.N_SU, ExamCode.JUNE,
                "6월 평가원", LocalDate.of(YEAR, 6, 4), 1);
        august = ExamMaster.academyExam(null, YEAR, GradeType.N_SU, ExamCode.MONTHLY,
                "8월 더 프리미엄", LocalDate.of(YEAR, 8, 18), 2);
        admission = ExamMaster.common(YEAR, GradeType.N_SU, ExamCode.SEPT, "작년 9평", 1);
        for (ExamMaster e : List.of(june, august, admission)) {
            e.addSubject("KOREAN", "국어", 1).acceptRawScore(e.isAcademyExam());
            e.addSubject("ENGLISH", "영어", 2, false, false, true).acceptRawScore(e.isAcademyExam());
            em.persist(e);
        }
        em.flush();
    }

    private void score(ExamMaster exam, short korean) {
        gradeService.saveAcademyScores(student, exam, List.of(
                new ScoreInput(exam.activeSubjects().get(0).getId(), (short) 125, (short) 90, (short) 2, korean),
                new ScoreInput(exam.activeSubjects().get(1).getId(), null, null, (short) 1, (short) 92)));
        em.flush();
    }

    @Test
    @DisplayName("★ 입학 전 성적은 섞이지 않는다 — 시안도 두 화면으로 나눈다")
    void excludesAdmissionScores() {
        score(june, (short) 80);
        gradeService.mine(student).addScore(admission.activeSubjects().get(0),
                (short) 110, (short) 70, (short) 4);
        em.flush();

        assertThat(queryService.exams(student))
                .extracting(AcademyExamQueryService.ExamSummary::examName)
                .containsExactly("6월 평가원");
    }

    @Test
    @DisplayName("본 시험만 최근순으로 나온다 — 안 본 회차가 빈 카드로 뜨면 안 된다")
    void listsTakenExamsNewestFirst() {
        score(june, (short) 80);
        score(august, (short) 85);

        assertThat(queryService.exams(student))
                .extracting(AcademyExamQueryService.ExamSummary::examName)
                .containsExactly("8월 더 프리미엄", "6월 평가원");
    }

    @Test
    @DisplayName("★ 영어는 원점수와 등급만 — 없는 표준점수·백분위는 0 이 아니라 null 이다")
    void absoluteGradedSubjectHasNulls() {
        score(august, (short) 85);

        var english = queryService.exam(student, august.getId()).subjects().stream()
                .filter(s -> s.subjectCode().equals("ENGLISH")).findFirst().orElseThrow();

        assertThat(english.rawScore()).isEqualTo((short) 92);
        assertThat(english.gradeLevel()).isEqualTo((short) 1);
        assertThat(english.standardScore()).isNull();
        assertThat(english.percentile()).isNull();
    }

    @Test
    @DisplayName("★ 지망대학 진단과 기준점수까지 남은 점수가 나온다")
    void returnsUniversityDiagnosis() {
        score(august, (short) 85);
        em.persist(new ExamUniversityChoice(student, august, (short) 1, "가나대", "국어국문",
                5, 22, 11, "국수영사", new BigDecimal("479"), new BigDecimal("511"), "위험"));
        em.flush();

        var detail = queryService.exam(student, august.getId());

        assertThat(detail.choices()).hasSize(1);
        assertThat(detail.choices().get(0).gapToCutoff()).isEqualByComparingTo("32");
        assertThat(detail.noDiagnosisByType()).isFalse();
    }

    @Test
    @DisplayName("★ 평가원은 진단이 원래 없다고 알려준다 — '안 적어서' 빈 것과 구분해야 한다")
    void kiceHasNoDiagnosisByType() {
        score(june, (short) 80);

        var detail = queryService.exam(student, june.getId());

        assertThat(detail.choices()).isEmpty();
        assertThat(detail.noDiagnosisByType()).isTrue();
        assertThat(detail.exam().kice()).isTrue();
    }

    @Test
    @DisplayName("안 본 회차는 찾을 수 없다 — 존재 여부를 알려주지 않는다")
    void notTakenExamIsNotFound() {
        score(june, (short) 80);

        assertThatThrownBy(() -> queryService.exam(student, august.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("성적 변화는 오래된 순이다 — 그래프가 시간 순서로 그려진다")
    void trendIsChronological() {
        score(august, (short) 85);
        score(june, (short) 80);

        assertThat(queryService.trend(student))
                .extracting(p -> p.exam().examName())
                .containsExactly("6월 평가원", "8월 더 프리미엄");
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 관리자 웹도 학생별로 본다 — 앱과 같은 모양")
    void adminSeesSameAsApp() {
        score(june, (short) 80);
        var admin = new com.dlab.common.security.AuthPrincipal(1L, "branch", bundang.getId(),
                java.util.Set.of(com.dlab.common.security.Role.BRANCH_ADMIN), false, false);

        assertThat(adminController.studentAcademyExams(admin, student.getId()).data())
                .extracting(AcademyExamQueryService.ExamSummary::examName)
                .containsExactly("6월 평가원");
        assertThat(adminController.studentAcademyExam(admin, student.getId(), june.getId())
                .data().subjects()).isNotEmpty();
        assertThat(adminController.studentAcademyTrend(admin, student.getId()).data()).hasSize(1);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("다른 지점 학생 성적은 열리지 않는다")
    void adminOtherBranchDenied() {
        score(june, (short) 80);
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();
        var other = new com.dlab.common.security.AuthPrincipal(1L, "branch", ilsan.getId(),
                java.util.Set.of(com.dlab.common.security.Role.BRANCH_ADMIN), false, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adminController.studentAcademyExams(other, student.getId()))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class);
    }
}
