package com.dlab.domain.notification.repository;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.entity.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByEventCode(NotificationEvent eventCode);

    boolean existsByEventCode(NotificationEvent eventCode);

    /** 관리 화면 목록. 이벤트 순으로 고정해 매핑표처럼 읽히게 한다. */
    List<NotificationTemplate> findAllByDeletedFalseOrderByEventCode();

    /**
     * 직원 수동 발송용 템플릿만 (디랩 1016 「직원 발송」).
     *
     * <p>⚠️ <b>발송 가능한 것만 걸러서 주지 않는다.</b> 심사 대기 중인 것도 목록에 담아야
     * 화면이 「보여주되 고르지 못하게」 할 수 있다 — 빼면 담당자는 템플릿이 없는 줄 안다.
     */
    List<NotificationTemplate> findAllByManualTrueAndDeletedFalseOrderByEventCode();

    /** 심사 진행 상황 조회 — 카카오 심사(E-5)가 리드타임이 길어 따로 본다. */
    List<NotificationTemplate> findByReviewStatusInAndDeletedFalseOrderByEventCode(
            List<ReviewStatus> statuses);
}
