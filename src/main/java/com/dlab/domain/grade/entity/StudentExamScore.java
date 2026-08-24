package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 과목 하나의 점수.
 *
 * <p><b>세 칸이 전부 nullable이다.</b> 미응시 과목·절대평가 과목·기억나지 않는 칸이
 * 실제로 있고, 0으로 채우면 진짜 0점과 구분되지 않는다.
 */
@Getter
@Entity
@Table(name = "student_exam_score")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentExamScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private StudentGradeSubmission submission;

    /**
     * 과목이 회차를 알고 있지만 <b>비정규화해 들고 있는다</b> —
     * "6월 성적만" 같은 조회가 과목 테이블 조인 없이 끝나야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_master_id", nullable = false)
    private ExamMaster examMaster;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_subject_id", nullable = false)
    private ExamSubject examSubject;

    @Column(name = "standard_score")
    private Short standardScore;

    @Column(name = "percentile")
    private Short percentile;

    @Column(name = "grade_level")
    private Short gradeLevel;

    StudentExamScore(StudentGradeSubmission submission, ExamSubject examSubject,
                     Short standardScore, Short percentile, Short gradeLevel) {
        this.submission = submission;
        this.examSubject = examSubject;
        this.examMaster = examSubject.getExamMaster();
        this.standardScore = standardScore;
        this.percentile = percentile;
        this.gradeLevel = gradeLevel;
    }

    /** 세 칸이 모두 비었으면 입력한 것이 없다 — 저장할 이유가 없다. */
    public boolean isBlank() {
        return standardScore == null && percentile == null && gradeLevel == null;
    }
}
