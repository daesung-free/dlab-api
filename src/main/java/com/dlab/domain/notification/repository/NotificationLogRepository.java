package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByDedupKey(String dedupKey);
}
