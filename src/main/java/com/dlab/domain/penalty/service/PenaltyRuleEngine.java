package com.dlab.domain.penalty.service;

import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.penalty.repository.PenaltyRuleRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상벌점 자동부여 엔진.
 *
 * <p><b>규칙을 코드에 박지 않는다.</b> 트리거→점수 매핑(I-5)이 미확정이라
 * {@code penalty_rule} 테이블을 읽어 적용만 한다. 확정되면 행만 채우면 되고,
 * 규칙이 없으면 아무 일도 일어나지 않는다(부작용 없음).
 *
 * <p><b>점수는 항목에서 가져와 복사한다.</b> 부여 시점의 값을 {@code penalty_point.points}에
 * 남겨야 항목 점수를 나중에 바꿔도 과거 이력이 소급해서 바뀌지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PenaltyRuleEngine {

    private final PenaltyRuleRepository ruleRepository;
    private final PenaltyPointRepository pointRepository;

    /**
     * 트리거를 적용한다. 매칭되는 활성 규칙이 없으면 빈 목록.
     *
     * <p>부모 트랜잭션과 분리한다({@code REQUIRES_NEW}) — 상벌점 부여가 실패해도
     * 출결 기록 자체는 남아야 한다. 태깅이 롤백되면 학생이 등원한 사실이 사라진다.
     *
     * @param condition 트리거 조건값.
     *                  <b>출결</b>이면 {@code att_gn}(S/T/A/D/N/C/R) 또는 {@code ABSENT} —
     *                  결석은 태깅 이벤트가 아니라 코드가 없어서 확정 배치가 이 값으로 건다.
     *                  <b>루틴</b>이면 결과 상태({@code NOT_SUBMITTED}·{@code ABSENT} 등),
     *                  <b>정기일정</b>이면 {@code NOT_RECOGNIZED}
     * @param occurredOn 발생 일자. 멱등키 구성에 쓴다 — 같은 날 같은 규칙은 한 번만 부여된다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PenaltyPoint> apply(StudentEnrollment enrollment,
                                    PenaltyTriggerType triggerType,
                                    String condition,
                                    LocalDate occurredOn) {
        List<PenaltyRule> rules = ruleRepository.findActive(
                enrollment.getAcademy().getId(), enrollment.getYear(), triggerType);

        List<PenaltyPoint> applied = new ArrayList<>();
        for (PenaltyRule rule : rules) {
            if (!rule.matches(condition)) {
                continue;
            }
            grant(enrollment, rule, triggerType, occurredOn).ifPresent(applied::add);
        }
        return applied;
    }

    private java.util.Optional<PenaltyPoint> grant(StudentEnrollment enrollment,
                                                   PenaltyRule rule,
                                                   PenaltyTriggerType triggerType,
                                                   LocalDate occurredOn) {
        String key = PenaltyPoint.attendanceKey(enrollment.getId(), occurredOn, rule.getId());

        // 먼저 확인해서 흔한 중복은 예외 없이 걸러낸다.
        // 다만 이것만으로는 동시 실행을 못 막는다 — 최종 방어선은 아래 DB 유니크 제약이다.
        if (pointRepository.existsByIdempotencyKeyAndDeletedFalse(key)) {
            return java.util.Optional.empty();
        }

        PenaltyPoint point = new PenaltyPoint(
                enrollment.getAcademy(),
                enrollment,
                rule.getPenaltyItem(),
                // 항목 점수를 그대로 복사한다. 자동부여는 조정하지 않는다.
                rule.getPenaltyItem().getPointValue(),
                rule.getPenaltyItem().getItemName(),
                sourceOf(triggerType),
                key);

        try {
            return java.util.Optional.of(pointRepository.saveAndFlush(point));
        } catch (DataIntegrityViolationException e) {
            // 두 인스턴스가 동시에 통과한 경우. 정상 흐름이라 경고로만 남긴다.
            log.info("상벌점 중복 부여 차단 - key={}", key);
            return java.util.Optional.empty();
        }
    }

    private PenaltySource sourceOf(PenaltyTriggerType triggerType) {
        return triggerType == PenaltyTriggerType.ATTENDANCE
                ? PenaltySource.KIOSK
                : PenaltySource.ROUTINE;
    }
}
