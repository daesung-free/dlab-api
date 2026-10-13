package com.dlab.domain.access.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 제공 시간창을 여는 <b>로컬 전용</b> 스위치.
 *
 * <h2>왜 필요한가</h2>
 * 긴급 출입 성공 화면은 <b>아침 7시, 문 앞에 선 학생</b>에게만 보인다. 낮에는 무조건
 * 거절이라 <b>번호가 실제로 뜨는 화면을 확인할 방법이 없고</b>, 거기서 처음 깨지면
 * 다음 날 아침까지 아무도 모른다.
 *
 * <h2>★★ 운영에 새지 않게 두 겹으로 막는다</h2>
 * 시간창은 <b>이 기능의 유일한 제한</b>이다. 운영에서 꺼지면 아무 때나 번호가 나간다.
 * 그래서 설정 한 줄로 열리게 두지 않는다.
 *
 * <ol>
 *   <li><b>프로필</b> — 이 빈은 {@code local} 프로필에서만 등록된다. 운영은
 *       {@code prod} 로 뜨므로 <b>이 클래스가 애초에 존재하지 않는다.</b>
 *       설정값은 어디서든 넣을 수 있지만 프로필은 기동 명령에 박혀 있다</li>
 *   <li><b>플래그</b> — 그 위에 {@code emergency-access.window.always-open} 이
 *       {@code true} 여야 한다. 기본값은 {@code false} 이므로 로컬에서도
 *       <b>명시적으로 켜야</b> 열린다</li>
 * </ol>
 *
 * <p>둘 중 하나라도 빠지면 <b>실제 판정으로 그대로 넘긴다</b> — 끄는 쪽이 기본이다.
 *
 * <p>★ 그리고 열려 있으면 <b>기동 로그와 매 요청에 경고를 남긴다.</b> 조용히 열려 있으면
 * 「왜 아무 때나 번호가 나오지」를 한참 뒤에 알게 된다.
 */
@Slf4j
@Primary
@Component
@Profile("local")
public class LocalOpenWindow implements DoorCodeService.OpenWindow {

    private final DoorCodeService.ScheduledOpenWindow real;
    private final boolean alwaysOpen;

    /**
     * ★ 플래그를 <b>생성자로 받는다.</b> 필드 주입으로 두면 이 동작을 확인하려고
     * {@code local} 프로필 스프링 컨텍스트를 띄워야 하는데, 그러면 테스트가
     * <b>실제 로컬 DB·Redis 에 붙는다</b> — 테스트가 개발 중인 DB 를 건드리면 안 된다.
     */
    public LocalOpenWindow(DoorCodeService.ScheduledOpenWindow real,
                           @Value("${emergency-access.window.always-open:false}")
                           boolean alwaysOpen) {
        this.real = real;
        this.alwaysOpen = alwaysOpen;
    }

    @PostConstruct
    void warn() {
        if (alwaysOpen) {
            log.warn("""

                    ====================================================================
                     ⚠️  긴급 출입 제공 시간창이 열려 있습니다 (로컬 전용 스위치)
                        실제 창: {}  →  지금은 하루 종일 번호가 제공됩니다.
                        운영 프로필(prod)에서는 이 스위치가 아예 등록되지 않습니다.
                        끄려면: emergency-access.window.always-open: false
                    ====================================================================
                    """, DoorCodeService.ScheduledOpenWindow.describe());
        }
    }

    @Override
    public boolean isOpen() {
        if (!alwaysOpen) {
            return real.isOpen();
        }
        log.warn("긴급 출입 시간창 우회(로컬 전용) — 실제 창은 {}",
                DoorCodeService.ScheduledOpenWindow.describe());
        return true;
    }
}
