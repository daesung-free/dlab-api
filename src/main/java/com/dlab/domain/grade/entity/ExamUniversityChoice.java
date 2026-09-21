package com.dlab.domain.grade.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모의고사 지망대학 진단 한 줄 (앱 시안 4.6).
 *
 * <h2>★ 판정은 연구소가 한 것이다</h2>
 * 기준점수·가능성진단·지원자 중 석차를 <b>우리가 계산하지 않는다.</b> 받은 그대로 둔다.
 *
 * <h2>실적 관리와 다른 데이터다</h2>
 * {@code AdmissionResult} 는 <b>실제로 지원한 대학과 합불</b>이고, 이건 <b>모의고사 볼 때 적은
 * 희망 대학과 그 회차 기준의 가능성</b>이다. 회차마다 바뀐다 — 섞지 말 것.
 */
@Getter
@Entity
@Table(name = "exam_university_choice")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamUniversityChoice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_master_id", nullable = false)
    private ExamMaster examMaster;

    /** 1지망 / 2지망 */
    @Column(name = "choice_rank", nullable = false)
    private short choiceRank;

    @Column(name = "university_name", nullable = false, length = 100)
    private String universityName;

    @Column(name = "department_name", length = 100)
    private String departmentName;

    @Column(name = "recruit_quota")
    private Integer recruitQuota;

    @Column(name = "applicant_count")
    private Integer applicantCount;

    @Column(name = "applicant_rank")
    private Integer applicantRank;

    /** 적용된 수능영역("국수영사") — 예상점수의 근거 */
    @Column(name = "applied_areas", length = 20)
    private String appliedAreas;

    /** 소수점이 붙는 대학이 있다(환산점수) */
    @Column(name = "expected_score", precision = 7, scale = 2)
    private BigDecimal expectedScore;

    @Column(name = "cutoff_score", precision = 7, scale = 2)
    private BigDecimal cutoffScore;

    /** 연구소 표기 그대로 — enum 으로 묶으면 표기가 바뀔 때 저장이 막힌다 */
    @Column(length = 10)
    private String diagnosis;

    public ExamUniversityChoice(StudentEnrollment enrollment, ExamMaster examMaster,
                                short choiceRank, String universityName, String departmentName,
                                Integer recruitQuota, Integer applicantCount,
                                Integer applicantRank, String appliedAreas,
                                BigDecimal expectedScore, BigDecimal cutoffScore,
                                String diagnosis) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.examMaster = examMaster;
        this.choiceRank = choiceRank;
        this.universityName = universityName;
        this.departmentName = departmentName;
        this.recruitQuota = recruitQuota;
        this.applicantCount = applicantCount;
        this.applicantRank = applicantRank;
        this.appliedAreas = appliedAreas;
        this.expectedScore = expectedScore;
        this.cutoffScore = cutoffScore;
        this.diagnosis = diagnosis;
    }

    /**
     * 기준점수까지 남은 점수. 음수면 넘은 것이다.
     *
     * <p>시안 4.6 이 단계 이름과 함께 이 숫자를 보여준다 — 지망을 적은 학생의 약 84% 가
     * 「위험」이라 단계 이름만 보이면 무엇을 해야 할지 알 수 없다.
     */
    public BigDecimal gapToCutoff() {
        if (expectedScore == null || cutoffScore == null) {
            return null;
        }
        return cutoffScore.subtract(expectedScore);
    }
}
