package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseTimeEntity;
import com.dlab.domain.user.entity.Branch;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.UserAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 사전 제출 결석/지각 사유.
 * 미등원 알림 배치는 반려(REJECTED)되지 않은 사유가 있는 학생을 대상에서 제외한다
 * — 무단결석만 알림 대상(CLAUDE.md §3).
 */
@Getter
@Entity
@Table(name = "absence_reason")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceReason extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 20)
    private AbsenceReasonType reasonType;

    @Column(nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AbsenceReasonStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_account_id")
    private UserAccount reviewer;

    public AbsenceReason(Branch branch, Student student, LocalDate targetDate,
                         AbsenceReasonType reasonType, String content) {
        this.branch = branch;
        this.student = student;
        this.targetDate = targetDate;
        this.reasonType = reasonType;
        this.content = content;
        this.status = AbsenceReasonStatus.PENDING;
        this.submittedAt = Instant.now();
    }

    public void review(UserAccount reviewer, boolean approved) {
        this.reviewer = reviewer;
        this.status = approved ? AbsenceReasonStatus.APPROVED : AbsenceReasonStatus.REJECTED;
        this.reviewedAt = Instant.now();
    }
}
