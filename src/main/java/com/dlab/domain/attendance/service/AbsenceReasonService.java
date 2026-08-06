package com.dlab.domain.attendance.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사유 신청 관리 (F-4.1-6).
 *
 * <p>DSA는 관리자가 직접 등록·수정하는 단일 구조였다. <b>앱 실시간 제출 →
 * 승인 주체별 라우팅</b>이 신규다. 관리자 직접 등록도 병행한다(시트 명시).
 *
 * <h2>승인·반려는 여기 없다</h2>
 * 이미 {@code ApprovalService}와 {@code /api/v1/admin/approvals}가 한다.
 * 목록에 {@code approvalRequestId}를 실어 보내면 화면이 그 API를 그대로 부르면 된다 —
 * <b>승인 로직을 두 벌 만들면 타임아웃·에스컬레이션 처리가 갈린다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbsenceReasonService {

    /** 학부모 미응답 에스컬레이션 후보 기준. 화면 통계가 "2시간+"로 표시한다. */
    private static final Duration ESCALATION_THRESHOLD = Duration.ofHours(2);

    private final AbsenceReasonRepository absenceReasonRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final ApprovalService approvalService;
    private final Clock clock;

    /**
     * 관리자 직접 등록.
     *
     * <p><b>등록해도 자동 승인은 아니다.</b> 승인 라우팅을 그대로 탄다 —
     * 관리자가 넣었다고 건너뛰면 학부모 승인이 필요한 유형(결석·조퇴)에서
     * 학부모가 모르는 사이에 처리된다.
     *
     * @param startTime 외출·조퇴 시작. 결석·지각은 종일이라 {@code null}
     * @param endTime   외출 종료. 조퇴는 복귀가 없어 {@code null}
     */
    @Transactional
    public AbsenceReason register(AuthPrincipal me, Long enrollmentId, LocalDate date,
                                  AbsenceReasonType type, String reasonText,
                                  LocalTime startTime, LocalTime endTime) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        validatePeriod(type, startTime, endTime);

        AbsenceReason reason = absenceReasonRepository.save(new AbsenceReason(
                enrollment.getAcademy(), enrollment, date, type, reasonText, startTime, endTime));

        ApprovalRequest approval = approvalService.create(enrollment, RequestType.ABSENCE_REASON);
        reason.linkApproval(approval);

        log.info("사유신청 등록(관리자): enrollmentId={}, 유형={}, 일자={}", enrollmentId, type, date);
        return reason;
    }

    /**
     * 시간 범위 검증.
     *
     * <p><b>외출은 종료가 있어야 한다.</b> 없으면 언제 돌아오는지 알 수 없어
     * 복귀 태깅과 대조할 수 없다. 조퇴는 반대로 복귀가 없으므로 종료를 받으면 안 된다.
     */
    private void validatePeriod(AbsenceReasonType type, LocalTime startTime, LocalTime endTime) {
        if (type == AbsenceReasonType.OUTING) {
            if (startTime == null || endTime == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "외출은 시작·종료 시각이 모두 필요합니다.");
            }
            if (!endTime.isAfter(startTime)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "종료 시각이 시작보다 빠릅니다.");
            }
            return;
        }
        if (type == AbsenceReasonType.EARLY_LEAVE && startTime == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조퇴는 시작 시각이 필요합니다.");
        }
    }

    /**
     * 관리자 목록.
     *
     * @param status 화면 탭(대기/승인/반려). {@code null}이면 전체
     */
    @Transactional(readOnly = true)
    public List<AbsenceRequestRow> list(AuthPrincipal me, LocalDate from, LocalDate to,
                                        ApprovalStatus status) {
        Long academyId = academyOf(me);

        List<AbsenceReason> reasons =
                absenceReasonRepository.findByAcademyAndPeriod(academyId, from, to);

        Map<Long, ClassAssignment> classes = classesOf(reasons);
        Instant now = Instant.now(clock);

        return reasons.stream()
                .filter(r -> status == null || statusOf(r) == status)
                .map(r -> toRow(r, classes.get(r.getEnrollment().getId()), now))
                .toList();
    }

    /** 화면 상단 통계 5칸. */
    @Transactional(readOnly = true)
    public Map<String, Long> summary(AuthPrincipal me, LocalDate from, LocalDate to) {
        List<AbsenceRequestRow> pending = list(me, from, to, ApprovalStatus.PENDING);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", (long) pending.size());
        counts.put("waitingParent", pending.stream()
                .filter(r -> r.approverType() == ApproverType.PARENT).count());
        counts.put("waitingTeacher", pending.stream()
                .filter(r -> r.approverType() == ApproverType.TEACHER).count());
        counts.put("escalationCandidate", pending.stream()
                .filter(AbsenceRequestRow::escalationCandidate).count());
        // 벌점 확정 충돌은 I-10(확정 기준) 미확정이라 아직 셀 수 없다.
        // 0을 내리되 화면이 "충돌 없음"으로 오해하지 않게 별도 키로 둔다
        counts.put("penaltyConflictUnavailable", 0L);
        return counts;
    }

    private AbsenceRequestRow toRow(AbsenceReason r, ClassAssignment assignment, Instant now) {
        ApprovalRequest approval = r.getApprovalRequest();
        ApproverType approverType = approval == null ? null
                : approval.getApprovalItem().getApproverType();

        boolean escalationCandidate = approval != null
                && approval.getStatus() == ApprovalStatus.PENDING
                && approverType == ApproverType.PARENT
                && Duration.between(approval.getRequestedAt(), now)
                        .compareTo(ESCALATION_THRESHOLD) > 0;

        return new AbsenceRequestRow(
                r.getId(),
                approval == null ? null : approval.getId(),
                r.getSubmittedAt(),
                r.getEnrollment().getStudentNo(),
                r.getEnrollment().getStudent().getName(),
                assignment == null ? null : assignment.getClassMaster().getName(),
                r.getReasonType(),
                r.periodLabel(),
                r.getReasonText(),
                approverType,
                statusOf(r),
                escalationCandidate);
    }

    /**
     * 신청 상태.
     *
     * <p>승인 요청이 아직 안 붙은 건은 <b>대기</b>로 본다 — 제출은 됐고 라우팅 전이다.
     */
    private ApprovalStatus statusOf(AbsenceReason r) {
        return r.getApprovalRequest() == null
                ? ApprovalStatus.PENDING
                : r.getApprovalRequest().getStatus();
    }

    private Map<Long, ClassAssignment> classesOf(List<AbsenceReason> reasons) {
        Map<Long, ClassAssignment> result = new HashMap<>();
        reasons.forEach(r -> classAssignmentRepository
                .findActiveFixedByEnrollmentId(r.getEnrollment().getId())
                .ifPresent(a -> result.put(r.getEnrollment().getId(), a)));
        return result;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    private Long academyOf(AuthPrincipal me) {
        Long academyId = me.academyScopeFilter();
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return academyId;
    }

    /**
     * @param approvalRequestId 승인·반려는 {@code /api/v1/admin/approvals/{id}}를 그대로 부른다.
     *                          여기서 다시 구현하지 않는다
     * @param escalationCandidate 학부모가 2시간 넘게 미응답. 화면 통계가 이걸 센다
     */
    public record AbsenceRequestRow(
            Long id,
            Long approvalRequestId,
            Instant submittedAt,
            String studentNo,
            String name,
            String className,
            AbsenceReasonType type,
            String period,
            String reason,
            ApproverType approverType,
            ApprovalStatus status,
            boolean escalationCandidate
    ) {
    }
}
