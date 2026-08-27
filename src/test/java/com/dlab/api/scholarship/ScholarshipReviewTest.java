package com.dlab.api.scholarship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.*;
import com.dlab.domain.scholarship.entity.*;
import com.dlab.domain.scholarship.service.ScholarshipReviewService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
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
            assertThat(r.getDetectedValue()).isEqualTo(9);
            assertThat(r.getThreshold()).isEqualTo(5);
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
