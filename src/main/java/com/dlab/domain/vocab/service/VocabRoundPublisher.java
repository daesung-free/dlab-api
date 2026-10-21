package com.dlab.domain.vocab.service;

import com.dlab.domain.user.repository.AcademyRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 회차 공개 배치 (v1 5.4).
 *
 * <p><i>"회차는 예정일이 되면 공개되고, 이후 계속 공개 상태를 유지한다."</i>
 *
 * <h2>★ 공개를 응시 시점에 계산하지 않는다</h2>
 * 「학생이 들어올 때 계산해서 보여 주면」 저장이 필요 없어 보이지만, 그러면 <b>시험 없는 날을
 * 뒤늦게 추가했을 때 이미 응시한 회차의 범위가 바뀐다</b>(v1 5.6). 그래서 날짜가 되면
 * 저장해 둔다.
 *
 * <h2>★ 지점을 하나씩 돈다 — 한 지점이 실패해도 나머지는 공개된다</h2>
 * 한 트랜잭션으로 묶으면 설정이 깨진 지점 하나 때문에 <b>11개 지점 전체가 공개되지 않는다.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VocabRoundPublisher {

    private final VocabScheduleService scheduleService;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    /**
     * 매일 새벽 공개.
     *
     * <p>⚠️ 공개 <b>시각</b>은 미확정이다(v1 Q5). 새벽에 돌려 <b>그날 아침에는 이미 열려
     * 있게</b> 한다 — 늦게 돌리면 학생이 「오늘 시험이 없다」로 본다.
     *
     * <p>★ <b>멱등하다.</b> 이미 저장된 회차는 건너뛰므로 두 번 돌아도 안전하고,
     * 하루 빠뜨렸다면 다음 실행이 <b>밀린 회차까지 함께</b> 공개한다.
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void publish() {
        short year = (short) LocalDate.now(clock).getYear();
        int total = 0;
        for (var academy : academyRepository.findAll()) {
            try {
                total += scheduleService.publishDueRounds(academy.getId(), year);
            } catch (RuntimeException e) {
                // ★ 한 지점이 터져도 나머지는 공개한다 — 멈추면 그날 전 지점이 안 열린다
                log.error("D:VOCA 회차 공개 실패 — 지점 {}", academy.getId(), e);
            }
        }
        if (total > 0) {
            log.info("D:VOCA 회차 {}건 공개", total);
        }
    }
}
