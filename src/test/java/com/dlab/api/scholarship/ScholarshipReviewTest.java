package com.dlab.api.scholarship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.*;
import com.dlab.domain.master.entity.Scholarship;
import com.dlab.domain.scholarship.entity.*;
import com.dlab.domain.scholarship.service.ScholarshipReviewService;
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
 * 장학 취소 판정 (0820 규정 · 2026-08-26 승인).
 *
 * <p>지키려는 것은 셋 — <b>자동으로 취소하지 않는다</b>,
 * <b>규칙이 꺼져 있으면 아무 일도 없다</b>, <b>근거 없이 걸지 않는다</b>.
 */
@SpringBootTest
@Transactional
class ScholarshipReviewTest {

    @Autowired ScholarshipReviewService reviewService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2095;

    Academy academy;
    StudentEnrollment student;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("SC1", "장학테스트", LocalTime.of(9, 0));
        em.persist(academy);

        Student person = new Student("SC-0001", "김장학", "010-0000-0000");
        em.persist(person);
        student = new StudentEnrollment(person, academy, YEAR, "2095-0001", null, GradeType.N_SU);
        em.persist(student);
        // ★ 장학이 있어야 취소 판정 대상이 된다
        em.persist(new Scholarship(academy, student, "KICE_30", new BigDecimal("30.00")));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private ScholarshipCancelRule rule(CancelRuleType type, int threshold, String subjects,
                                       boolean active) {
        ScholarshipCancelRule r = new ScholarshipCancelRule(null, YEAR, type, threshold, subjects);
        if (active) {
            r.changeActive(true);
        }
        em.persist(r);
        em.flush();
        return r;
    }

    /** 등급합 대안 한 줄. {@code group}이 다르면 서로 OR 대안이다. */
    private ScholarshipCancelRule gradeRule(String scholarshipType, int group, int threshold,
                                            String subjects, String examCodes,
                                            ElectiveMode elective, String extraSubject,
                                            Short extraMaxGrade) {
        ScholarshipCancelRule r = new ScholarshipCancelRule(
                null, YEAR, CancelRuleType.EXAM_GRADE_SUM, threshold, subjects);
        r.updateGradeSumOptions(scholarshipType, (short) group, examCodes, elective,
                extraSubject, extraMaxGrade);
        r.changeActive(true);
        em.persist(r);
        em.flush();
        return r;
    }

    /** 6·9월 평가원 양식과 점수를 심는다. */
    private void examScores(Integer juneSum, Integer septSum) {
        StudentGradeSubmission submission = new StudentGradeSubmission(student);
        em.persist(submission);

        for (var pair : List.of(new Object[]{ExamCode.JUNE, juneSum},
                new Object[]{ExamCode.SEPT, septSum})) {
            ExamCode code = (ExamCode) pair[0];
            Integer sum = (Integer) pair[1];
            if (sum == null) {
                continue;
            }
            ExamMaster exam = ExamMaster.common(YEAR, GradeType.N_SU, code, code.name(), 1);
            // 국·수·영 세 과목. 합이 sum 이 되도록 나눠 넣는다
            ExamSubject k = exam.addSubject("KOREAN", "국어", 1);
            ExamSubject m = exam.addSubject("MATH", "수학", 2);
            ExamSubject e = exam.addSubject("ENGLISH", "영어", 3);
            em.persist(exam);

            int base = sum / 3;
            int rest = sum - base * 2;
            submission.addScore(k, null, null, (short) base);
            submission.addScore(m, null, null, (short) base);
            submission.addScore(e, null, null, (short) rest);
        }
        em.flush();
    }

    /** 회차 하나에 과목별 등급을 그대로 심는다. */
    private void grades(ExamCode code, java.util.Map<String, Integer> subjectGrades) {
        StudentGradeSubmission submission = gradeSubmission();
        ExamMaster exam = ExamMaster.common(YEAR, GradeType.N_SU, code, code.name(), 1);
        int order = 1;
        var subjects = new java.util.LinkedHashMap<String, ExamSubject>();
        for (String code0 : subjectGrades.keySet()) {
            subjects.put(code0, exam.addSubject(code0, code0, order++));
        }
        em.persist(exam);
        subjectGrades.forEach((c, g) ->
                submission.addScore(subjects.get(c), null, null, g.shortValue()));
        em.flush();
    }

    private StudentGradeSubmission submission;

    private StudentGradeSubmission gradeSubmission() {
        if (submission == null) {
            submission = new StudentGradeSubmission(student);
            em.persist(submission);
        }
        return submission;
    }

    // ─────────────────────────────────────────── 규칙이 꺼져 있으면

    @Test
    @DisplayName("★ 규칙이 꺼져 있으면 아무 일도 일어나지 않는다 — 미검증 규칙이 돌면 안 된다")
    void inactiveRuleDoesNothing() {
        rule(CancelRuleType.PENALTY_POINT, 40, null, false);

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★ 더프리미엄 미응시는 켜도 판정되지 않는다 — 응시 이력이 우리에게 없다")
    void mockExamAbsenceCannotBeJudged() {
        rule(CancelRuleType.MOCK_EXAM_ABSENCE, 2, null, true);

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
        assertThat(CancelRuleType.MOCK_EXAM_ABSENCE.isJudgeable()).isFalse();
    }

    // ─────────────────────────────────────────── 등급합

    @Test
    @DisplayName("★ 등급합이 기준을 넘으면 검토 대상이 된다 — 취소가 아니다")
    void gradeSumOverThresholdBecomesReview() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, null);   // 6월 등급합 9 > 5

        List<ScholarshipReview> created = reviewService.judge(admin, academy.getId(), YEAR);

        assertThat(created).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(ReviewStatus.PENDING);   // ★ CANCELED 가 아니다
            // ⚠️ 등급합은 2배 스케일로 저장된다 — 탐구 2과목 평균이 .5로 떨어질 수 있어서다
            assertThat(r.getDetectedValue()).isEqualTo(18);
            assertThat(r.getThreshold()).isEqualTo(10);
            assertThat(r.getDetail()).contains("등급합");
        });
    }

    @Test
    @DisplayName("기준을 충족하면 걸리지 않는다")
    void gradeSumWithinThreshold() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(3, null);

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★★ 6월·9월 중 좋은 쪽을 본다 — 나쁜 쪽을 쓰면 한 번 못 본 시험에 장학이 날아간다")
    void usesBetterOfJuneAndSept() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, 3);   // 6월은 미달이지만 9월은 충족

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★ 대상 과목이 비어 있으면 판정하지 않는다 — 임의로 고르면 엉뚱한 학생이 걸린다")
    void noSubjectsMeansNoJudgement() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, null, true);
        examScores(9, null);

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★ 성적을 안 낸 학생은 대상이 아니다 — '미제출'과 '기준 미달'은 다르다")
    void missingSubmissionIsNotJudged() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    // ─────────────────────────────────────────── 대안 · AND 조건 · 탐구 집계 (0826)

    @Test
    @DisplayName("★★ 장학이 없는 학생은 판정 대상이 아니다 — 취소할 것도 적용할 기준도 없다")
    void studentWithoutScholarshipIsNotJudged() {
        Student p = new Student("SC-0002", "장학없음", "010-1111-1111");
        em.persist(p);
        StudentEnrollment other = new StudentEnrollment(p, academy, YEAR, "2095-0002", null,
                GradeType.N_SU);
        em.persist(other);
        rule(CancelRuleType.PENALTY_POINT, 0, null, true);   // 0점이라 누구나 걸리는 규칙
        em.flush();

        // 장학이 있는 student 만 올라온다
        assertThat(reviewService.judge(admin, academy.getId(), YEAR))
                .allSatisfy(r -> assertThat(r.getEnrollment().getId()).isEqualTo(student.getId()));
    }

    @Test
    @DisplayName("★ 다른 장학의 기준은 적용되지 않는다")
    void ruleOfOtherScholarshipTypeIsIgnored() {
        // 학생은 KICE_30 인데 규칙은 CSAT_100 용
        gradeRule("CSAT_100", 1, 4, "KOREAN,MATH,ENGLISH", "JUNE,SEPT", null, null, null);
        grades(ExamCode.JUNE, java.util.Map.of("KOREAN", 5, "MATH", 5, "ENGLISH", 5));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★★ 대안 중 하나만 충족해도 통과한다 — 유리한 쪽을 골라주는 게 규정 취지다")
    void passingOneAlternativeIsEnough() {
        // 대안1: 국+수+탐1 ≤ 4 (미달) / 대안2: 국+수+영 ≤ 4 (충족)
        gradeRule("KICE_30", 1, 4, "KOREAN,MATH", "JUNE", ElectiveMode.SINGLE, null, null);
        gradeRule("KICE_30", 2, 4, "KOREAN,MATH,ENGLISH", "JUNE", null, null, null);
        grades(ExamCode.JUNE, java.util.Map.of(
                "KOREAN", 1, "MATH", 1, "ENGLISH", 2, "INQUIRY1", 7, "INQUIRY2", 8));

        // 대안1은 1+1+7=9 로 미달이지만 대안2가 1+1+2=4 로 충족
        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("모든 대안이 미달이면 걸리고, 근거에 대안이 전부 남는다")
    void allAlternativesFailed() {
        gradeRule("KICE_30", 1, 4, "KOREAN,MATH", "JUNE", ElectiveMode.SINGLE, null, null);
        gradeRule("KICE_30", 2, 4, "KOREAN,MATH,ENGLISH", "JUNE", null, null, null);
        grades(ExamCode.JUNE, java.util.Map.of(
                "KOREAN", 3, "MATH", 3, "ENGLISH", 3, "INQUIRY1", 3, "INQUIRY2", 4));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).singleElement()
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(ReviewStatus.PENDING);
                    assertThat(r.getDetail()).contains("모든 대안 미달")
                            .contains("탐구1과목").contains("KOREAN·MATH·ENGLISH");
                });
    }

    @Test
    @DisplayName("★★ 영어 등급 조건을 못 채우면 등급합이 충족돼도 걸린다 — 등급합만 보면 규정과 달라진다")
    void extraConditionMustAlsoBeMet() {
        gradeRule("KICE_30", 1, 5, "KOREAN,MATH", "JUNE", ElectiveMode.SINGLE,
                "ENGLISH", (short) 2);
        // 등급합 1+1+1=3 으로 기준 5 충족이지만 영어가 4등급
        grades(ExamCode.JUNE, java.util.Map.of(
                "KOREAN", 1, "MATH", 1, "ENGLISH", 4, "INQUIRY1", 1, "INQUIRY2", 9));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).singleElement()
                .satisfies(r -> assertThat(r.getDetail()).contains("ENGLISH 2등급 이내 미충족"));
    }

    @Test
    @DisplayName("탐구 1과목은 좋은 쪽을 쓴다")
    void singleElectiveUsesBetterOne() {
        gradeRule("KICE_30", 1, 5, "KOREAN,MATH", "JUNE", ElectiveMode.SINGLE, null, null);
        // 탐구가 2와 9면 2를 쓴다 → 2+2+2 = 6 ... 이 아니라 기준 5 초과라 걸린다
        grades(ExamCode.JUNE, java.util.Map.of(
                "KOREAN", 2, "MATH", 2, "INQUIRY1", 9, "INQUIRY2", 2));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).singleElement()
                .satisfies(r -> assertThat(r.getDetail()).contains("등급합 6"));
    }

    @Test
    @DisplayName("★★ 탐구 2과목 평균이 .5로 떨어져도 경계에서 어긋나지 않는다")
    void avg2HandlesHalfGrade() {
        gradeRule("KICE_30", 1, 4, "KOREAN,MATH", "CSAT", ElectiveMode.AVG2, null, null);
        // 1 + 1 + (3+4)/2 = 5.5 > 4 → 걸린다. 반올림하면 5 or 6 으로 흔들린다
        grades(ExamCode.CSAT, java.util.Map.of(
                "KOREAN", 1, "MATH", 1, "INQUIRY1", 3, "INQUIRY2", 4));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).singleElement()
                .satisfies(r -> {
                    assertThat(r.getDetail()).contains("등급합 5.5");
                    assertThat(r.getDetectedValue()).isEqualTo(11);   // 5.5 × 2
                });
    }

    @Test
    @DisplayName("★ 탐구 2과목 평균은 한 과목만 있으면 판정하지 않는다 — 빠진 쪽을 0으로 세면 통과한다")
    void avg2NeedsBothElectives() {
        gradeRule("KICE_30", 1, 4, "KOREAN,MATH", "JUNE", ElectiveMode.AVG2, null, null);
        grades(ExamCode.JUNE, java.util.Map.of("KOREAN", 9, "MATH", 9, "INQUIRY1", 9));

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("★ 수능 기준 규칙은 수능 회차를 본다 — 평가원 성적으로 판정하면 안 된다")
    void csatRuleReadsCsatExam() {
        gradeRule("KICE_30", 1, 4, "KOREAN,MATH,ENGLISH", "CSAT", null, null, null);
        grades(ExamCode.JUNE, java.util.Map.of("KOREAN", 9, "MATH", 9, "ENGLISH", 9));

        // 6월 성적은 아무리 나빠도 수능 기준 규칙에 안 걸린다
        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }

    // ─────────────────────────────────────────── 확정·예외

    @Test
    @DisplayName("사람이 취소를 확정한다")
    void humanConfirmsCancel() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, null);
        ScholarshipReview review = reviewService.judge(admin, academy.getId(), YEAR).get(0);
        em.flush();

        reviewService.cancel(admin, review.getId(), "기준 미달 확인");

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.CANCELED);
        assertThat(review.getDecidedBy()).isEqualTo(1L);
        assertThat(review.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("★ 예외 인정에는 사유가 필수다 — 없으면 '왜 살려뒀나'에 답할 수 없다")
    void exceptionRequiresNote() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, null);
        ScholarshipReview review = reviewService.judge(admin, academy.getId(), YEAR).get(0);
        em.flush();

        assertThatThrownBy(() -> reviewService.except(admin, review.getId(), "  "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.SCHOLARSHIP_EXCEPTION_NOTE_REQUIRED);

        reviewService.except(admin, review.getId(), "개인사정 응시 불가 — 더프모 성적으로 대체");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.EXCEPTED);
    }

    @Test
    @DisplayName("이미 처리된 건은 다시 처리할 수 없다")
    void alreadyDecidedCannotBeChanged() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, null);
        ScholarshipReview review = reviewService.judge(admin, academy.getId(), YEAR).get(0);
        reviewService.cancel(admin, review.getId(), "확정");
        em.flush();

        assertThatThrownBy(() -> reviewService.except(admin, review.getId(), "번복"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.SCHOLARSHIP_REVIEW_ALREADY_DECIDED);
    }

    @Test
    @DisplayName("★ 판정을 다시 돌려도 같은 건이 중복으로 쌓이지 않는다")
    void rejudgeDoesNotDuplicate() {
        rule(CancelRuleType.EXAM_GRADE_SUM, 5, "KOREAN,MATH,ENGLISH", true);
        examScores(9, null);
        reviewService.judge(admin, academy.getId(), YEAR);
        em.flush();

        assertThat(reviewService.judge(admin, academy.getId(), YEAR)).isEmpty();
    }
}
