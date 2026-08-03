package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByEventCode(NotificationEvent eventCode);
}
