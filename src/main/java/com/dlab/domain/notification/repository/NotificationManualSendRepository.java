package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationManualSend;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationManualSendRepository
        extends JpaRepository<NotificationManualSend, Long> {

    /**
     * 이미 보냈는가.
     *
     * <p>⚠️ <b>이 조회가 중복을 막는 수단이 아니다.</b> 동시에 두 번 눌리면 둘 다
     * "안 보냈다"를 보고 둘 다 보낸다 — 막는 것은 {@code UNIQUE (preview_id)} 뿐이다.
     * 이건 사용자에게 <b>409 를 먼저 깔끔하게</b> 돌려주기 위한 것이다.
     */
    boolean existsByPreviewId(Long previewId);
}
