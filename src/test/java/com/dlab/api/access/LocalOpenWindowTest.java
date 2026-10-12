package com.dlab.api.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.access.service.DoorCodeService;
import com.dlab.domain.access.service.LocalOpenWindow;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 제공 시간창 로컬 스위치 (긴급출입).
 *
 * <p>★ 지키려는 것은 <b>운영에 새지 않는 것</b> 하나다. 시간창이 이 기능의 유일한
 * 제한이라, 운영에서 꺼지면 아무 때나 번호가 나간다.
 *
 * <p>⚠️ <b>{@code local} 프로필을 띄워서 확인하지 않는다.</b> 그러면 테스트가
 * {@code application-local.yml} 을 읽어 <b>개발 중인 실제 DB·Redis 에 붙는다.</b>
 * 플래그 동작은 생성자로 직접 넣어 확인하고, 스프링에서는 <b>빈이 없다는 것만</b> 본다.
 */
@SpringBootTest
@TestPropertySource(properties = "emergency-access.window.always-open=true")
class LocalOpenWindowTest {

    @Autowired ApplicationContext context;
    @Autowired DoorCodeService.ScheduledOpenWindow real;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    @Test
    @DisplayName("★★ local 이 아니면 스위치 빈이 등록되지 않는다 — 플래그를 켜도 효과가 없다")
    void switchDoesNotExistOutsideLocal() {
        // 설정값은 어디서든 넣을 수 있지만(위 @TestPropertySource 가 켜 뒀다)
        // 프로필은 기동 명령에 박혀 있다 — 그래서 운영에서는 이 클래스가 아예 없다
        assertThat(context.getBeanNamesForType(LocalOpenWindow.class)).isEmpty();
        assertThat(context.getBean(DoorCodeService.OpenWindow.class))
                .isInstanceOf(DoorCodeService.ScheduledOpenWindow.class);
    }

    @Test
    @DisplayName("★ 플래그가 꺼져 있으면 실제 판정 그대로다 — 기본값은 끄는 쪽이다")
    void defaultsToRealWindow() {
        var off = new LocalOpenWindow(real, false);

        assertThat(off.isOpen()).isEqualTo(real.isOpen());
    }

    @Test
    @DisplayName("플래그를 켜면 시간과 무관하게 열린다")
    void alwaysOpenWhenEnabled() {
        // 실제 창 밖인 시각을 주어도 열린다
        var midnight = new DoorCodeService.ScheduledOpenWindow(
                Clock.fixed(java.time.Instant.parse("2026-10-12T15:00:00Z"),  // KST 24:00
                        java.time.ZoneOffset.UTC));
        assertThat(midnight.isOpen()).isFalse();

        assertThat(new LocalOpenWindow(midnight, true).isOpen()).isTrue();
    }
}
