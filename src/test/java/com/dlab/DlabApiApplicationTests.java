package com.dlab;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트 기동 + Flyway 마이그레이션 + JPA 매핑(ddl-auto=validate) 검증.
 * 엔티티와 마이그레이션이 어긋나면 여기서 바로 깨진다.
 *
 * <p>실행 전 docker-compose로 로컬 PostgreSQL이 떠 있어야 한다(src/test/resources/application.yml 참고).
 */
@SpringBootTest
class DlabApiApplicationTests {

    @Autowired
    private NotificationTemplateRepository notificationTemplateRepository;

    @Test
    void contextLoads() {
    }

    @Test
    @DisplayName("알림 이벤트-채널 매핑 seed가 모든 이벤트를 덮는다")
    void everyEventHasTemplate() {
        for (NotificationEvent event : NotificationEvent.values()) {
            assertThat(notificationTemplateRepository.findByEventCode(event))
                    .as("이벤트 %s 의 템플릿", event)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("문구는 아직 미확정 상태여야 한다 (운영팀 확정 전 실제 발송 금지)")
    void templatesAreNotContentConfirmedYet() {
        assertThat(notificationTemplateRepository.findAll())
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.isContentConfirmed()).isFalse());
    }
}
