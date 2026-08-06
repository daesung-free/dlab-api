package com.dlab.domain.appconfig.repository;

import com.dlab.domain.appconfig.entity.NotificationPreference;
import com.dlab.domain.notification.entity.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByAccountId(Long accountId);

    Optional<NotificationPreference> findByAccountIdAndEventCode(
            Long accountId, NotificationEvent eventCode);

    /**
     * 이 계정이 해당 알림을 <b>끄지 않았는가</b>.
     *
     * <p>행이 없으면 수신이므로 "꺼진 행이 존재하지 않는가"로 판정한다 —
     * "켜진 행이 있는가"로 물으면 설정을 한 번도 안 만진 사람에게 알림이 안 간다.
     */
    boolean existsByAccountIdAndEventCodeAndEnabledFalse(Long accountId, NotificationEvent eventCode);
}
