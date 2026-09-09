package com.dlab.domain.attendance.entity;

import com.dlab.domain.audit.AuditEntityListener;
import com.dlab.domain.audit.Audited;
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
@Audited("사유 신청")
@Entity
@Table(name = "absence_reason")
@EntityListeners(AuditEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceReason extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /**
     * 사유 카테고리(병결·가정사 등).
     *
     * <p><b>{@code null}이 정상이다</b> — 카테고리가 하나도 등록되지 않은 상태에서도
     * 사유 제출이 되어야 한다. 필수로 걸면 값이 확정될 때까지 앱에서 사유를 못 낸다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private AbsenceReasonCategory category;

    public void changeCategory(AbsenceReasonCategory category) {
        this.category = category;
    }

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

    /**
     * 외출·조퇴 시작 시각. 결석·지각은 종일이라 {@code null}이다.
     *
     * <p>화면 "기간" 컬럼이 이걸로 {@code "13:00 ~ 15:00"} / {@code "16:30 이후"} / {@code "종일"}을
     * 만든다. 그리고 <b>정기일정 인정 판정</b>이 "등록 시간 대비 실제 출입 30분 이상 차이"를
     * 보므로 일자만으로는 계산이 안 된다.
     */
    @Column(name = "start_time")
    private java.time.LocalTime startTime;

    /** 외출 종료 시각. 조퇴는 복귀가 없어 {@code null}이다. */
    @Column(name = "end_time")
    private java.time.LocalTime endTime;

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

    public AbsenceReason(Academy academy, StudentEnrollment enrollment,
                         LocalDate attendanceDate, AbsenceReasonType reasonType, String reasonText,
                         java.time.LocalTime startTime, java.time.LocalTime endTime) {
        this(academy, enrollment, attendanceDate, reasonType, reasonText);
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** 화면 "기간" 표기. 서버가 만들어 내린다 — 화면마다 조립하면 표기가 갈린다. */
    public String periodLabel() {
        if (startTime == null) {
            return "종일";
        }
        return endTime == null ? startTime + " 이후" : startTime + " ~ " + endTime;
    }

    public void linkApproval(ApprovalRequest approvalRequest) {
        this.approvalRequest = approvalRequest;
    }
}
