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
    @DisplayName("★ 아직 실제로 나갈 수 있는 템플릿이 하나도 없어야 한다")
    void noTemplateIsSendableYet() {
        // ★★ 전에는 「문구가 전부 미확정이어야 한다」였다. 그런데 2026-10-16 에 출결 알림
        //    5종 문구가 실제로 확정돼 들어왔고(디랩), 그때부터 그 단정은 **문구가 왔다는
        //    사실만으로 깨진다** — 막으려던 것은 문구 확정 여부가 아니라 **실수로 나가는 것**이다.
        //
        //    그래서 단정을 발송 가능 여부로 옮긴다. 알림톡은 카카오 심사(APPROVED)를
        //    통과해야만 나가므로, 문구가 확정돼도 등록·심사 전까지는 막혀 있다.
        //    ⚠️ 이 테스트가 깨지면 **심사도 안 끝난 문구가 나갈 수 있는 상태**라는 뜻이다
        assertThat(notificationTemplateRepository.findAll())
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.isSendable())
                        .as("템플릿 %s 가 벌써 발송 가능하다", t.getEventCode())
                        .isFalse());
    }
}
