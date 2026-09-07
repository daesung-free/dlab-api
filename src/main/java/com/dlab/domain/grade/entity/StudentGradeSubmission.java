package com.dlab.domain.grade.entity;

import com.dlab.domain.audit.AuditEntityListener;
import com.dlab.domain.audit.Audited;
import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 학생이 낸 성적 한 벌 (등록 건당 1개).
 *
 * <p>내신은 값이 하나뿐이라({@code 주요교과평균}) 별도 테이블 없이 컬럼으로 둔다.
 *
 * <h2>★ "성적을 모른다"를 표현할 수 있어야 한다</h2>
 * 가입 시점에 성적표가 없는 학생이 실제로 있다(자퇴·검정고시·분실). 필수로 막으면
 * 가입 자체를 못 하고, 그렇다고 0을 채우게 두면 <b>통계에서 진짜 0점과 구분되지 않는다.</b>
 * 그래서 {@link #examSkipped} + 사유로 남기고 점수 행은 만들지 않는다.
 */
@Getter
@Audited("성적")
@Entity
@Table(name = "student_grade_submission")
@EntityListeners(AuditEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentGradeSubmission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /**
     * 내신 주요교과평균.
     *
     * <p>등급(1.00~9.00)으로 적는 학생과 원점수로 적는 학생이 섞이므로 <b>범위를 좁게
     * 잡지 않는다.</b> 해석은 상담 교사가 한다 — 양식에도 단위 표기가 없다.
     */
    @Column(name = "main_subject_average", precision = 5, scale = 2)
    private BigDecimal mainSubjectAverage;

    @Column(name = "exam_skipped", nullable = false)
    private boolean examSkipped = false;

    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * 마지막으로 고친 직원.
     *
     * <p><b>{@code createdBy}로는 답이 안 된다</b> — 그건 {@code updatable = false}라
     * 최초 작성자(= 학생)만 남는다. 이 값이 장학 취소 판정의 근거이므로, 학생 입력값을
     * 직원이 고쳤다면 그 사실이 남아야 한다.
     *
     * <p>{@code null}이면 학생이 낸 그대로다.
     */
    @Column(name = "modified_by")
    private Long modifiedBy;

    @Column(name = "modified_at")
    private Instant modifiedAt;

    /** 직원이 고쳤음을 기록한다. 학생 본인 입력 경로에서는 부르지 않는다. */
    public void markModifiedBy(Long accountId, Instant at) {
        this.modifiedBy = accountId;
        this.modifiedAt = at;
    }

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<StudentExamScore> scores = new ArrayList<>();

    public StudentGradeSubmission(StudentEnrollment enrollment) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
    }

    public void updateSchoolRecord(BigDecimal mainSubjectAverage) {
        this.mainSubjectAverage = mainSubjectAverage;
    }

    /**
     * 모의고사 성적을 통째로 건너뛴다.
     *
     * <p>이미 넣어둔 점수는 <b>지운다</b>. 남겨두면 "모른다고 했는데 점수가 있는" 상태가
     * 되어 상담 화면이 무엇을 믿어야 할지 정해지지 않는다.
     */
    public void skipExams(String reason) {
        this.examSkipped = true;
        this.skipReason = reason;
        scores.forEach(BaseEntity::markDeleted);
    }

    /** 다시 점수를 넣기 시작하면 건너뛴 상태가 풀린다. */
    public void unskipExams() {
        this.examSkipped = false;
        this.skipReason = null;
    }

    public StudentExamScore addScore(ExamSubject subject, Short standardScore,
                                     Short percentile, Short gradeLevel) {
        StudentExamScore score = new StudentExamScore(this, subject,
                subject.accept(ExamSubject.ScoreField.STANDARD_SCORE, standardScore),
                subject.accept(ExamSubject.ScoreField.PERCENTILE, percentile),
                subject.accept(ExamSubject.ScoreField.GRADE_LEVEL, gradeLevel));
        scores.add(score);
        return score;
    }

    /** 회차 하나를 통째로 다시 쓴다 — 기존 행은 soft delete하고 새로 넣는다. */
    public void clearScoresOf(Long examMasterId) {
        scores.stream()
                .filter(s -> !s.isDeleted())
                .filter(s -> s.getExamMaster().getId().equals(examMasterId))
                .forEach(BaseEntity::markDeleted);
    }

    public List<StudentExamScore> activeScores() {
        return scores.stream().filter(s -> !s.isDeleted()).toList();
    }

    public void markSubmitted(Instant at) {
        this.submittedAt = at;
    }

    /** 아직 아무것도 안 낸 상태인가. 온보딩 라우팅이 쓴다. */
    public boolean isEmpty() {
        return mainSubjectAverage == null && !examSkipped && activeScores().isEmpty();
    }
}
