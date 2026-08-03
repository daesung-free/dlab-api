package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 사전 제출 결석/지각 사유.
 *
 * <p>승인 상태를 자체 컬럼으로 갖지 않는다 — {@link ApprovalRequest}에 위임한다.
 * 미등원 알림 배치는 사유가 있는 학생을 대상에서 제외한다(무단결석만 알림).
 */
@Getter
@Entity
@Table(name = "absence_reason")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceReason extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 20)
    private AbsenceReasonType reasonType;

    @Column(name = "reason_text", nullable = false, length = 500)
    private String reasonText;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    /** 당일 자정. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_request_id")
    private ApprovalRequest approvalRequest;

    public AbsenceReason(Academy academy, StudentEnrollment enrollment,
                         LocalDate attendanceDate, AbsenceReasonType reasonType, String reasonText) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.attendanceDate = attendanceDate;
        this.reasonType = reasonType;
        this.reasonText = reasonText;
    }

    public void linkApproval(ApprovalRequest approvalRequest) {
        this.approvalRequest = approvalRequest;
    }
}
