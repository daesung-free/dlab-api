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
}
