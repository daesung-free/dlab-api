package com.dlab.domain.access.repository;

import com.dlab.domain.access.entity.DoorCodeRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DoorCodeRequestRepository extends JpaRepository<DoorCodeRequest, Long> {

    /**
     * 관리자 내역 (관리자 웹 10번).
     *
     * <p>지점을 비우면 전 지점이다 — 본사가 아침에 한눈에 본다.
     * 학생을 못 찾은 건도 나오므로 {@code enrollment} 는 {@code LEFT JOIN} 이다.
     */
    @Query("""
            SELECT r FROM DoorCodeRequest r
            JOIN FETCH r.academy
            LEFT JOIN FETCH r.enrollment e
            LEFT JOIN FETCH e.student
            WHERE r.requestedAt >= :from AND r.requestedAt < :to
              AND (:academyId IS NULL OR r.academy.id = :academyId)
              AND r.deleted = false
            ORDER BY r.requestedAt DESC, r.id DESC
            """)
    List<DoorCodeRequest> search(@Param("academyId") Long academyId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);
}
