package com.dlab.domain.approval.repository;

import com.dlab.domain.approval.entity.*;
import com.dlab.domain.user.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Optional<ApprovalRequest> findByEnrollmentIdAndApprovalItemIdAndStatus(
            Long enrollmentId, Long approvalItemId, ApprovalStatus status);

    List<ApprovalRequest> findByAcademyIdAndStatusOrderByRequestedAtAsc(Long academyId, ApprovalStatus status);

    /**
     * 관리자 현황 — 지점 단위. <b>담임 개인 대기열과 다른 질의다.</b>
     *
     * <p>담임용은 "내가 처리할 것"이고 이쪽은 "지금 몇 건이 밀려 있나"다.
     * 그래서 상태를 지정하지 않으면 처리된 건까지 함께 본다 — 대기 건만 주면
     * "오늘 몇 건이 들어왔나"를 셀 수 없다.
     *
     * @param academyId 전 지점 권한자가 지점을 안 고르면 {@code null}이고, 그때는 전 지점이다
     */
    @Query("""
            SELECT r FROM ApprovalRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            JOIN FETCH r.approvalItem i
            WHERE r.deleted = false
              AND (:academyId IS NULL OR r.academy.id = :academyId)
              AND (:status IS NULL OR r.status = :status)
              AND (:requestType IS NULL OR i.requestType = :requestType)
              AND r.requestedAt >= :from AND r.requestedAt < :to
            ORDER BY r.requestedAt DESC
            """)
    List<ApprovalRequest> findBoard(Long academyId, ApprovalStatus status,
                                    RequestType requestType, Instant from, Instant to);

    /**
     * 이 학부모 계정이 승인해야 할 대기 건.
     * 연결된 자녀(사람) 기준이라 등록 건이 바뀌어도 계속 보인다.
     */
    @Query("""
            SELECT r FROM ApprovalRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student s
            JOIN FETCH r.approvalItem
            WHERE r.status = com.dlab.domain.approval.entity.ApprovalStatus.PENDING
              AND r.deleted = false
              AND EXISTS (
                    SELECT 1 FROM StudentGuardianLink l, Account a
                    WHERE l.student = s AND a.guardian = l.guardian AND a.id = :accountId)
            ORDER BY r.requestedAt ASC
            """)
    List<ApprovalRequest> findPendingForGuardianAccount(Long accountId);

    /**
     * 이 선생님이 에스컬레이션 대상인 대기 건.
     * 담당선생님은 반배정에서 자동 결정되므로 신청 시점 스냅샷을 그대로 조건에 쓴다.
     */
    @Query("""
            SELECT r FROM ApprovalRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            JOIN FETCH r.approvalItem
            WHERE r.status = com.dlab.domain.approval.entity.ApprovalStatus.PENDING
              AND r.deleted = false
              AND r.escalationTeacher.id = :teacherId
            ORDER BY r.requestedAt ASC
            """)
    List<ApprovalRequest> findPendingForTeacher(Long teacherId);

    /**
     * 대기중일 때만 상태를 전이시키는 <b>원자적</b> 갱신.
     *
     * <p>학부모 승인과 담당선생님 승인이 정확히 같은 순간 들어올 수 있으므로, 조회 후 저장이
     * 아니라 조건부 UPDATE 한 방으로 처리한다. 반환값이 0이면 반대편이 먼저 처리한 것이므로
     * 호출부는 APPROVAL_ALREADY_PROCESSED로 응답한다.
     *
     * <p>낙관적 락(version 컬럼)은 쓰지 않는다 — 두 방식을 섞으면 어느 쪽이 실제로 동시성을
     * 막는지 불분명해진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ApprovalRequest r
               SET r.status          = :status,
                   r.resolvedAt      = :resolvedAt,
                   r.resolverType    = :resolverType,
                   r.resolverAccount = :resolver,
                   r.resolutionCase  = :resolutionCase,
                   r.rejectReason    = :rejectReason,
                   r.updatedAt       = :resolvedAt
             WHERE r.id = :id
               AND r.status = com.dlab.domain.approval.entity.ApprovalStatus.PENDING
            """)
    int resolveIfPending(Long id,
                         ApprovalStatus status,
                         Instant resolvedAt,
                         ApproverType resolverType,
                         Account resolver,
                         ResolutionCase resolutionCase,
                         String rejectReason);

    /**
     * 자동 재승인 요청 대상 — 학부모 우선인데 타임아웃이 지나도록 무응답이고 아직 안 보낸 건.
     *
     * <p>{@code reminder_sent_at IS NULL}이 <b>"1회만"의 실제 보장</b>이다.
     * 스케줄러가 두 번 돌아도 두 번째엔 안 걸린다.
     */
    @Query("""
            SELECT r FROM ApprovalRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.status = com.dlab.domain.approval.entity.ApprovalStatus.PENDING
              AND r.deleted = false
              AND r.primaryApprover = com.dlab.domain.approval.entity.ApproverType.PARENT
              AND r.reminderSentAt IS NULL
              AND r.escalationAt <= :now
            ORDER BY r.escalationAt ASC
            """)
    List<ApprovalRequest> findReminderTargets(Instant now);

    /**
     * 직원 이양 대상 — 재요청을 보낸 뒤 같은 대기시간이 또 지나도록 무응답인 건.
     *
     * <p>대기시간 비교는 조회로 좁히고 최종 판정은 엔티티가 한다 —
     * {@code timeout_minutes}가 건마다 다르라 SQL 한 줄로 정확히 거르기 어렵다.
     */
    @Query("""
            SELECT r FROM ApprovalRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.status = com.dlab.domain.approval.entity.ApprovalStatus.PENDING
              AND r.deleted = false
              AND r.primaryApprover = com.dlab.domain.approval.entity.ApproverType.PARENT
              AND r.reminderSentAt IS NOT NULL
              AND r.handedOverAt IS NULL
              AND r.reminderSentAt <= :now
            ORDER BY r.reminderSentAt ASC
            """)
    List<ApprovalRequest> findHandoverCandidates(Instant now);
}
