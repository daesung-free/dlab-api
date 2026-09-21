package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험 회차별 과목.
 *
 * <p><b>회차마다 따로 둔다.</b> 같은 학년이라도 한국사는 10월 학평·수능에만 붙어서,
 * 학년 단위로 한 벌만 두면 6월 시험에도 한국사 칸이 뜬다.
 */
@Getter
@Entity
@Table(name = "exam_subject")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamSubject extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_master_id", nullable = false)
    private ExamMaster examMaster;

    /**
     * 통계 축. 학년이 달라도 국어끼리는 묶이게 하는 값이라
     * 표시명({@link #subjectName})과 분리한다 — 표시명은 "통합사회"처럼 해마다 바뀐다.
     */
    @Column(name = "subject_code", nullable = false, length = 20)
    private String subjectCode;

    @Column(name = "subject_name", nullable = false, length = 30)
    private String subjectName;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /**
     * ★ 한국사는 절대평가라 표준점수·백분위가 없다.
     * 세 칸을 일괄로 열면 학생이 없는 점수를 지어내 채우고 그대로 통계에 들어간다.
     */
    @Column(name = "has_standard_score", nullable = false)
    private boolean hasStandardScore = true;

    @Column(name = "has_percentile", nullable = false)
    private boolean hasPercentile = true;

    @Column(name = "has_grade_level", nullable = false)
    private boolean hasGradeLevel = true;

    /**
     * 원점수를 받는가.
     *
     * <p>입학 전 성적 양식은 {@code false} 다 — 신상기록부에 원점수 칸이 없다. 디랩 시험은
     * 켠다: 영어·한국사가 절대평가라 <b>원점수와 등급만</b> 온다.
     */
    @Column(name = "has_raw_score", nullable = false)
    private boolean hasRawScore = false;

    ExamSubject(ExamMaster examMaster, String subjectCode, String subjectName, short sortOrder,
                boolean hasStandardScore, boolean hasPercentile, boolean hasGradeLevel) {
        this.examMaster = examMaster;
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.sortOrder = sortOrder;
        this.hasStandardScore = hasStandardScore;
        this.hasPercentile = hasPercentile;
        this.hasGradeLevel = hasGradeLevel;
    }

    /**
     * 이 과목이 받을 수 있는 칸인지. <b>양식에 없는 칸으로 들어온 값은 버린다</b> —
     * 한국사 표준점수처럼 존재하지 않는 값이 저장되면 평균이 조용히 오염된다.
     */
    public Short accept(ScoreField field, Short value) {
        if (value == null) {
            return null;
        }
        return switch (field) {
            case STANDARD_SCORE -> hasStandardScore ? value : null;
            case PERCENTILE -> hasPercentile ? value : null;
            case GRADE_LEVEL -> hasGradeLevel ? value : null;
            case RAW_SCORE -> hasRawScore ? value : null;
        };
    }

    /** 점수 칸 종류. */
    public enum ScoreField {
        STANDARD_SCORE, PERCENTILE, GRADE_LEVEL, RAW_SCORE
    }

    /** 원점수를 받게 한다. 디랩 시험 양식에서 쓴다. */
    public void acceptRawScore(boolean accept) {
        this.hasRawScore = accept;
    }
}
