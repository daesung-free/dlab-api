package com.dlab.domain.scholarship.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 장학 취소 <b>검토 대상</b> — 취소가 아니다.
 *
 * <h2>★ 판정은 자동, 확정은 사람</h2>
 * 시트가 <i>"개인사정에 의해 응시를 못할 경우 더프모 성적으로 대체하는 경우도 있다"</i>,
 * <i>"예외를 두는 경우가 많이 발생한다"</i>고 명시했다.
 * <b>자동으로 취소하면 예외인 학생 장학금이 조용히 날아간다.</b>
 * 클라이언트도 이 방식으로 회신했다(2026-08-26).
 *
 * <h2>판정 근거를 남긴다</h2>
 * {@link #detectedValue}(실제 값)와 {@link #threshold}(그때 임계값)를 함께 든다.
 * <b>"왜 걸렸는지"가 없으면</b> 담당자가 원본 데이터를 다시 뒤져야 하고,
 * 나중에 기준이 바뀌면 그때 판정을 재현할 수 없다.
 */
@Getter
@Entity
@Table(name = "scholarship_review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipReview extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30)
    private CancelRuleType ruleType;

    /** 걸린 실제 값 — 벌점 45점, 등급합 7 같은 것. */
    @Column(name = "detected_value", nullable = false)
    private int detectedValue;

    /** 판정 당시 임계값. 기준이 바뀌어도 <b>그때 왜 걸렸는지</b>가 남아야 한다. */
    @Column(nullable = false)
    private int threshold;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.PENDING;

    /** 예외로 넘긴 사유. 나중에 <b>"왜 살려뒀나"</b>에 답해야 한다. */
    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    public ScholarshipReview(StudentEnrollment enrollment, CancelRuleType ruleType,
                             int detectedValue, int threshold, String detail) {
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.enrollment = enrollment;
        this.ruleType = ruleType;
        this.detectedValue = detectedValue;
        this.threshold = threshold;
        this.detail = detail;
        this.status = ReviewStatus.PENDING;
    }

    /** 취소 확정. */
    public void cancel(Long decidedBy, String note, Instant at) {
        decide(ReviewStatus.CANCELED, decidedBy, note, at);
    }

    /**
     * 예외 인정. <b>사유가 필수다</b> — 없으면 나중에 왜 살려뒀는지 알 수 없다.
     */
    public void except(Long decidedBy, String note, Instant at) {
        decide(ReviewStatus.EXCEPTED, decidedBy, note, at);
    }

    private void decide(ReviewStatus status, Long decidedBy, String note, Instant at) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.decidedAt = at;
    }
}
