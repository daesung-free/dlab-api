package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationManualPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface NotificationManualPreviewRepository
        extends JpaRepository<NotificationManualPreview, Long> {

    /**
     * 공개 식별자로 미리보기 찾기.
     *
     * <p>템플릿·지점을 함께 가져온다 — 발송이 둘 다 필요하고, 건마다 다시 조회하면
     * 한 번 보낼 때 쿼리가 수신자 수만큼 늘어난다.
     */
    @Query("""
            SELECT p FROM NotificationManualPreview p
            JOIN FETCH p.template
            JOIN FETCH p.academy
            WHERE p.previewId = :previewId AND p.deleted = false
            """)
    Optional<NotificationManualPreview> findByPreviewId(UUID previewId);
}
