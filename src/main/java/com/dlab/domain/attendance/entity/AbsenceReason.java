package com.dlab.domain.attendance.entity;

import com.dlab.domain.audit.AuditLabel;
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
    @AuditLabel("구분")
    private AbsenceReasonCategory category;

    public void changeCategory(AbsenceReasonCategory category) {
        this.category = category;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "attendance_date", nullable = false)
    @AuditLabel("대상 일자")
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 20)
    @AuditLabel("사유 종류")
    private AbsenceReasonType reasonType;

    @Column(name = "reason_text", nullable = false, length = 500)
    @AuditLabel("사유 내용")
    private String reasonText;

    @Column(name = "submitted_at", nullable = false)
    @AuditLabel("제출 시각")
    private Instant submittedAt = Instant.now();

    /**
     * 외출·조퇴 시작 시각. 결석·지각은 종일이라 {@code null}이다.
     *
     * <p>화면 "기간" 컬럼이 이걸로 {@code "13:00 ~ 15:00"} / {@code "16:30 이후"} / {@code "종일"}을
     * 만든다. 그리고 <b>정기일정 인정 판정</b>이 "등록 시간 대비 실제 출입 30분 이상 차이"를
     * 보므로 일자만으로는 계산이 안 된다.
     */
    @Column(name = "start_time")
    @AuditLabel("시작 시각")
    private java.time.LocalTime startTime;

    /** 외출 종료 시각. 조퇴는 복귀가 없어 {@code null}이다. */
    @Column(name = "end_time")
    @AuditLabel("종료 시각")
    private java.time.LocalTime endTime;

    /** 당일 자정. */
    @Column(name = "deadline_at")
    @AuditLabel("제출 기한")
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

    /**
     * ★ 누가 냈는가. {@code null} 은 이 값이 생기기 전의 신청이다.
     *
     * <p>⚠️ 기존 행을 {@code STUDENT} 로 채우지 않았다 — <b>데스크 등록분이 섞여</b>
     * 있어서다. 그것까지 「학생이 냈다」로 바뀌면 <b>화면이 거짓을 말한다.</b>
     */
    @AuditLabel("신청자 구분")
    @Enumerated(EnumType.STRING)
    @Column(name = "submitter_type", length = 10)
    private AbsenceSubmitterType submitterType;

    /** 낸 계정. 관리자 등록 경로에서는 비어 있을 수 있다. */
    @AuditLabel("신청 계정")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_account_id")
    private com.dlab.domain.user.entity.Account submittedByAccount;

    /** 신청자를 남긴다. ★ 저장 직후 한 번만 부른다. */
    public void recordSubmitter(AbsenceSubmitterType type,
                                com.dlab.domain.user.entity.Account account) {
        this.submitterType = type;
        this.submittedByAccount = account;
    }

    /**
     * ★ 직원이 <b>증빙을 확인한</b> 시각 (디랩 1029 4-3 #36).
     *
     * <p>시트 원문: <b>「사유외출 및 조퇴 시 증빙영수증 제출할 경우 직원 확인 체크 시
     * 기존 부여된 벌점 자동 삭제」</b>
     */
    @AuditLabel("증빙 확인")
    @Column(name = "evidence_confirmed_at")
    private Instant evidenceConfirmedAt;

    @AuditLabel("증빙 확인자")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_confirmed_by")
    private com.dlab.domain.user.entity.Account evidenceConfirmedBy;

    /**
     * ★ 승인됐는데 <b>실제로 하지 않은</b> 것을 직원이 표시한 시각 (1029 4-3 #10).
     *
     * <p>⚠️ 벌점을 거둘지는 <b>{@code penalty_rule.supersedes_rule_id}</b> 로 정한다 —
     * 9-7 회신(「-1 을 취소할지 둘지」)과 <b>무관하게 데이터로 흡수된다.</b>
     */
    @AuditLabel("미진행 표시")
    @Column(name = "not_executed_at")
    private Instant notExecutedAt;

    @AuditLabel("미진행 표시자")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "not_executed_by")
    private com.dlab.domain.user.entity.Account notExecutedBy;

    /** ★ 증빙 확인. ⚠️ 두 번 누르면 거부한다 — 호출자가 판단한다. */
    public void confirmEvidence(com.dlab.domain.user.entity.Account staff, Instant at) {
        this.evidenceConfirmedAt = at;
        this.evidenceConfirmedBy = staff;
    }

    /** ★ 미진행 표시. */
    public void markNotExecuted(com.dlab.domain.user.entity.Account staff, Instant at) {
        this.notExecutedAt = at;
        this.notExecutedBy = staff;
    }

    public void linkApproval(ApprovalRequest approvalRequest) {
        this.approvalRequest = approvalRequest;
    }
}
