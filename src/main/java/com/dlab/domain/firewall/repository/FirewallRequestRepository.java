package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.UnlockStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirewallRequestRepository extends JpaRepository<FirewallRequest, Long> {

    Optional<FirewallRequest> findByApprovalRequestId(Long approvalRequestId);

    /**
     * 관리자 목록 — 지점·기간·학생·상태 필터.
     *
     * <p>조건이 비면 그 조건은 빠진다({@code :param IS NULL}) — 화면이 필터를 하나씩
     * 켜고 끄기 때문에 조합마다 메서드를 두면 감당이 안 된다.
     */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            JOIN FETCH r.approvalRequest
            WHERE r.academy.id = :academyId
              AND (:enrollmentId IS NULL OR e.id = :enrollmentId)
              AND (:status IS NULL OR r.unlockStatus = :status)
              AND (CAST(:from AS timestamp) IS NULL OR r.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR r.createdAt < :to)
              AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<FirewallRequest> search(@Param("academyId") Long academyId,
                                 @Param("enrollmentId") Long enrollmentId,
                                 @Param("status") UnlockStatus status,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    /** 현재 해제중. 관리자 화면이 가장 자주 여는 조회다. */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId
              AND r.unlockStatus = com.dlab.domain.firewall.entity.UnlockStatus.ACTIVE
              AND r.deleted = false
            ORDER BY r.unlockEndAt
            """)
    List<FirewallRequest> findActive(@Param("academyId") Long academyId);

    /** 만료 스케줄러가 훑는 경로 — 해제중인데 종료 시각이 지난 것. */
    @Query("""
            SELECT r FROM FirewallRequest r
            WHERE r.unlockStatus = com.dlab.domain.firewall.entity.UnlockStatus.ACTIVE
              AND r.unlockEndAt <= :at
              AND r.deleted = false
            """)
    List<FirewallRequest> findExpired(@Param("at") Instant at);
}
