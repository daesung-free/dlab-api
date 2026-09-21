package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 성적 입력 양식 — 시험 회차 (연도 × 학년).
 *
 * <p><b>과목·시험을 코드에 박지 않는 이유</b>는 학년마다 구성이 통째로 다르기 때문이다.
 * 실물 신상기록부 기준으로:
 * <ul>
 *   <li>예비고2·예비고3 — 6·9·10월 <b>학력평가</b>, 국어·수학·영어·통합사회·통합과학</li>
 *   <li>N수·현고3 — 6·9월 <b>평가원</b> + 전년도 <b>수능</b>, 국어·수학·영어·탐구1·탐구2</li>
 * </ul>
 * 여기에 2028 수능 개편으로 통합사회·통합과학이 또 바뀐다. 마스터로 두면 행만 넣으면 되고
 * 과거 학생 성적이 어느 과목이었는지도 그대로 남는다.
 *
 * <p>{@code academy}가 {@code null}이면 전 지점 공통이다. 조회는 <b>지점 행이 하나라도
 * 있으면 그것만</b> 쓴다 — 합치면 같은 시험이 두 번 나온다.
 */
@Getter
@Entity
@Table(name = "exam_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_type", nullable = false, length = 10)
    private GradeType gradeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_code", nullable = false, length = 20)
    private ExamCode examCode;

    /**
     * 화면 표시용 원문. 신상기록부에 적힌 그대로 넣는다("2026년 6월 학력평가").
     *
     * <p><b>서버가 연도를 조합해 만들지 않는다</b> — 수능은 응시 연도와 학년도가 어긋나
     * (2025년 11월 = 2026학년도) 조합식이 매번 틀린다.
     */
    @Column(name = "exam_name", nullable = false, length = 64)
    private String examName;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /**
     * 용도 — 입학 전 성적 / 디랩에서 본 시험.
     *
     * <p>★ <b>둘이 같은 행을 쓰면 업로드가 입학 성적을 지운다.</b> {@link ExamPurpose} 참고.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExamPurpose purpose = ExamPurpose.ADMISSION;

    /**
     * 시행일. <b>디랩 시험은 필수다.</b>
     *
     * <p>더프는 매월 치르는 월례고사라 코드({@code MONTHLY})만으로는 8월과 9월이 구분되지
     * 않는다 — 코드를 달마다 늘리지 않고 이 값으로 가른다.
     */
    @Column(name = "exam_date")
    private java.time.LocalDate examDate;

    @OneToMany(mappedBy = "examMaster", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("sortOrder ASC, id ASC")
    private List<ExamSubject> subjects = new ArrayList<>();

    public ExamMaster(Academy academy, short year, GradeType gradeType, ExamCode examCode,
                      String examName, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.gradeType = gradeType;
        this.examCode = examCode;
        this.examName = examName;
        this.sortOrder = sortOrder;
    }

    /** 전 지점 공통 회차 — 입학 전 성적 양식. */
    public static ExamMaster common(short year, GradeType gradeType, ExamCode examCode,
                                    String examName, int sortOrder) {
        return new ExamMaster(null, year, gradeType, examCode, examName, (short) sortOrder);
    }

    /**
     * 디랩에서 본 시험 회차.
     *
     * <p>시행일을 반드시 받는다 — 월례고사가 코드만으로는 구분되지 않는다.
     */
    public static ExamMaster academyExam(Academy academy, short year, GradeType gradeType,
                                         ExamCode examCode, String examName,
                                         java.time.LocalDate examDate, int sortOrder) {
        ExamMaster exam = new ExamMaster(academy, year, gradeType, examCode, examName,
                (short) sortOrder);
        exam.purpose = ExamPurpose.ACADEMY;
        exam.examDate = examDate;
        return exam;
    }

    public boolean isAcademyExam() {
        return purpose == ExamPurpose.ACADEMY;
    }

    public ExamSubject addSubject(String subjectCode, String subjectName, int sortOrder,
                                  boolean hasStandardScore, boolean hasPercentile,
                                  boolean hasGradeLevel) {
        ExamSubject subject = new ExamSubject(this, subjectCode, subjectName, (short) sortOrder,
                hasStandardScore, hasPercentile, hasGradeLevel);
        subjects.add(subject);
        return subject;
    }

    /** 표준 3종(표준점수·백분위·등급)을 모두 받는 과목. */
    public ExamSubject addSubject(String subjectCode, String subjectName, int sortOrder) {
        return addSubject(subjectCode, subjectName, sortOrder, true, true, true);
    }

    /** 살아 있는 과목만. soft delete된 과목은 양식에 뜨면 안 된다. */
    public List<ExamSubject> activeSubjects() {
        return subjects.stream().filter(s -> !s.isDeleted()).toList();
    }

    public void rename(String examName) {
        if (examName != null) {
            this.examName = examName;
        }
    }

    public boolean isCommon() {
        return academy == null;
    }
}
