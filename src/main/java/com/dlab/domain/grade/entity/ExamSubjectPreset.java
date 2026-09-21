package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학년별 과목 기본 구성.
 *
 * <p>디랩 시험 회차를 만들 때 과목을 비우면 이걸로 채운다. 매달 회차를 만들면서 과목·칸
 * 구성을 손으로 넣으면 한 칸만 틀려도 업로드에서 그 과목이 빠진다.
 *
 * <p><b>코드에 박지 않는다</b> — 통합수능 전환(2027년 11월)으로 학년별 과목이 또 바뀐다.
 * {@code academy}가 {@code null}이면 전 지점 공통이고, 지점 행이 있으면 그 지점은 그것만 쓴다.
 */
@Getter
@Entity
@Table(name = "exam_subject_preset")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamSubjectPreset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_type", nullable = false, length = 10)
    private GradeType gradeType;

    @Column(name = "subject_code", nullable = false, length = 20)
    private String subjectCode;

    @Column(name = "subject_name", nullable = false, length = 30)
    private String subjectName;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "has_standard_score", nullable = false)
    private boolean hasStandardScore;

    @Column(name = "has_percentile", nullable = false)
    private boolean hasPercentile;

    @Column(name = "has_grade_level", nullable = false)
    private boolean hasGradeLevel;

    @Column(name = "has_raw_score", nullable = false)
    private boolean hasRawScore;

    public ExamSubjectPreset(Academy academy, short year, GradeType gradeType,
                             String subjectCode, String subjectName, int sortOrder,
                             boolean hasStandardScore, boolean hasPercentile,
                             boolean hasGradeLevel, boolean hasRawScore) {
        this.academy = academy;
        this.year = year;
        this.gradeType = gradeType;
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.sortOrder = (short) sortOrder;
        this.hasStandardScore = hasStandardScore;
        this.hasPercentile = hasPercentile;
        this.hasGradeLevel = hasGradeLevel;
        this.hasRawScore = hasRawScore;
    }

    /** 다른 해로 복사한다. 롤오버가 쓴다. */
    public ExamSubjectPreset copyTo(short targetYear) {
        return new ExamSubjectPreset(academy, targetYear, gradeType, subjectCode, subjectName,
                sortOrder, hasStandardScore, hasPercentile, hasGradeLevel, hasRawScore);
    }

    public boolean isCommon() {
        return academy == null;
    }
}
