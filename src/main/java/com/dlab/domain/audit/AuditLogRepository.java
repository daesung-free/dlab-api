package com.dlab.domain.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 기간·지점·대상으로 훑는다.
     *
     * <p>{@code academyId}가 {@code null}이면 전 지점이다 — 본사가 지점을 안 고른 경우.
     * <p>{@code action}은 등록·수정·삭제 중 하나다. 비우면 전부다 — 화면이 보내던 값을
     * 여기서 받지 않아 <b>필터를 걸어도 조용히 무시되고 있었다</b>.
     *
     * <p><b>지점을 모르는 로그(마스터 변경)도 함께 보인다</b>: 그것도 누군가 바꾼 기록이라
     * 지점 필터가 걸렸다고 빠지면 "본사가 마스터를 고친 일"이 어디에도 안 남는다.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.deleted = false
              AND a.occurredAt >= :from AND a.occurredAt < :to
              AND (:academyId IS NULL OR a.academyId = :academyId OR a.academyId IS NULL)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:actorId IS NULL OR a.actorId = :actorId)
              AND (:action IS NULL OR a.action = :action)
            ORDER BY a.occurredAt DESC, a.id DESC
            """)
    Page<AuditLog> search(@Param("academyId") Long academyId,
                          @Param("entityType") String entityType,
                          @Param("actorId") Long actorId,
                          @Param("action") AuditAction action,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);

    /** 한 건이 어떻게 바뀌어 왔나 — 학생 상세에서 "이 기록의 이력". */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE a.deleted = false
              AND a.entityType = :entityType AND a.entityId = :entityId
            ORDER BY a.occurredAt DESC, a.id DESC
            """)
    List<AuditLog> findByEntity(@Param("entityType") String entityType,
                                @Param("entityId") Long entityId);
}
