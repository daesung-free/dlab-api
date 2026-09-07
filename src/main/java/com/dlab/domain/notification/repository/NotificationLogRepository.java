package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByDedupKey(String dedupKey);

    /**
     * 발송 이력 (F-4.4 · F-C-6).
     *
     * <p><b>채널별로 테이블을 나누지 않는다</b> — 알림톡과 푸시를 갈라 두면
     * "이 학생에게 언제 무엇이 갔나"를 한 번에 못 본다.
     *
     * <p>{@code sentAt}이 아니라 {@code createdAt}으로 훑는다. 실패·건너뜀 건은
     * {@code sentAt}이 비어 있는데, <b>그것도 이력이다</b> — 안 나간 것을 못 보면
     * "왜 안 왔지"에 답할 수 없다.
     */
    @Query("""
            SELECT l FROM NotificationLog l
            WHERE l.createdAt >= :from AND l.createdAt < :to
              AND (:academyId IS NULL OR l.academy.id = :academyId)
              AND (:event IS NULL OR l.eventCode = :event)
              AND (:channel IS NULL OR l.channel = :channel)
              AND (:status IS NULL OR l.status = :status)
              AND (:studentId IS NULL OR l.student.id = :studentId)
            ORDER BY l.createdAt DESC, l.id DESC
            """)
    Page<NotificationLog> search(@Param("academyId") Long academyId,
                                 @Param("event") NotificationEvent event,
                                 @Param("channel") NotificationChannel channel,
                                 @Param("status") NotificationStatus status,
                                 @Param("studentId") Long studentId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 Pageable pageable);

    /**
     * 발송 묶음 집계 — 화면의 "어제 미등원 알림이 몇 명에게 나갔나".
     *
     * <p><b>일자 × 이벤트 × 채널</b>로 묶는다. 자동발송은 배치 한 번에 수백 건이 나가는데
     * 그 실행을 묶는 키가 따로 없어, 실무에서 세는 단위가 이것이다.
     *
     * <p>알림톡은 건당 과금이라 <b>성공 수가 정산 근거</b>가 된다 — 대상 수만으로는 안 된다.
     */
    @Query("""
            SELECT CAST(l.createdAt AS date), l.eventCode, l.channel,
                   COUNT(l),
                   SUM(CASE WHEN l.status = com.dlab.domain.notification.entity.NotificationStatus.SENT
                            THEN 1 ELSE 0 END),
                   SUM(CASE WHEN l.status = com.dlab.domain.notification.entity.NotificationStatus.FAILED
                            THEN 1 ELSE 0 END),
                   SUM(CASE WHEN l.status = com.dlab.domain.notification.entity.NotificationStatus.SKIPPED
                            THEN 1 ELSE 0 END),
                   MIN(l.createdAt)
            FROM NotificationLog l
            WHERE l.createdAt >= :from AND l.createdAt < :to
              AND (:academyId IS NULL OR l.academy.id = :academyId)
            GROUP BY CAST(l.createdAt AS date), l.eventCode, l.channel
            ORDER BY CAST(l.createdAt AS date) DESC, l.eventCode
            """)
    List<Object[]> summarize(@Param("academyId") Long academyId,
                             @Param("from") Instant from,
                             @Param("to") Instant to);
}
