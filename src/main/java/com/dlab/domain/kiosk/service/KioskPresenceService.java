package com.dlab.domain.kiosk.service;

import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크가 마지막으로 통신한 시각을 남긴다.
 *
 * <h2>★★ 왜 필요한가</h2>
 * <b>단말이 꺼져 있으면 이탈 기록이 「0」이 아니라 「비는 것」</b>이다. 이 값이 없으면
 * 좌석표가 비었을 때 <b>「아무도 안 나갔다」와 「단말이 죽었다」를 가를 수 없다.</b>
 *
 * <p>⚠️ 키오스크는 <b>우리 장애를 알려주지 않는다</b>(CLAUDE.md §3) — 급식 조회가 실패하면
 * 전원 통과시키는 관대한 폴백이 있어 <b>우리 서버가 죽어도 단말 화면은 정상으로 보인다.</b>
 * 그래서 우리 쪽에서 따로 봐야 한다.
 *
 * <h2>★ 하트비트가 아니다</h2>
 * 키오스크는 주기적 신호를 보내지 않는다. 그래서 {@code /kiosk/**} <b>호출 시각</b>을 쓴다.
 * 정확한 생존 신호는 아니고 <b>한산한 시간대에는 오프라인으로 보일 수 있다</b> —
 * 그래도 「오늘 한 번도 안 불렀다」는 확실히 드러난다. 지금은 그것조차 못 본다.
 *
 * <h2>★★ 태깅을 막지 않는다</h2>
 * {@code REQUIRES_NEW} 로 떼고 예외를 삼킨다. 통신 기록이 실패해도 <b>학생이 등원한 사실은
 * 남아야 한다</b> — 상벌점 엔진과 같은 판단이다(§7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KioskPresenceService {

    private final BranchConfigRepository configRepository;
    private final Clock clock;

    /**
     * 호출을 봤다고 기록한다.
     *
     * <p>⚠️ <b>호출마다 UPDATE 하지 않는다</b> — 등·하원 시간대에는 한 지점에서 초당 여러 번
     * 들어와 같은 행을 계속 때린다. 간격 판정은 {@link BranchConfig#touchKioskSeen} 안에 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seen(Long academyId) {
        try {
            configRepository.findByAcademyIdAndDeletedFalse(academyId)
                    .ifPresent(config -> config.touchKioskSeen(clock.instant()));
        } catch (Exception e) {
            // ★ 삼킨다. 이 기록이 실패해도 출결·좌석 처리는 그대로 가야 한다
            log.warn("키오스크 통신 시각 기록 실패 — academyId={}", academyId, e);
        }
    }
}
