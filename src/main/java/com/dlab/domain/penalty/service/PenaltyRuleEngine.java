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
            // ★★ 먼저 거둔다 — ★ 순서가 중요하다 (디랩 1029 4-2: 「거두기 → 부여」).
            //
            //    ⚠️ 거꾸로 하면 ★ 같은 날·같은 학생에 두 벌점이 함께 존재하는 순간이
            //    생기고, 그 사이에 합계를 읽는 경로(제적 40점 판정)가 ★ 과하게 센다.
            supersede(enrollment, rule, occurredOn);
            grant(enrollment, rule, triggerType, occurredOn).ifPresent(applied::add);
        }
        return applied;
    }

    /**
     * 자동부여 취소 (디랩 1021 피드백 2-1).
     *
     * <p>디랩 원문: <i>"출결이 수정될 경우 벌점도 자동 부여되거나, <b>자동 취소</b>가
     * 가능하면 좋을것 같습니다."</i>
     *
     * <h2>★★ 멱등키로만 찾는다</h2>
     * 「지각 → 결석」으로 고치면 <b>지각 벌점을 거둬야 한다.</b> 그 건을 집는 유일한 길이
     * 멱등키({@code 학생:일자:규칙})다 — 사유 문자열로 찾으면 <b>담당자가 손으로 준
     * 같은 항목까지 같이 지워진다.</b>
     *
     * <p>★ <b>soft delete 다.</b> 물리 삭제하면 왜 사라졌는지 못 찾는다 — 학생이
     * 「벌점이 없어졌다」고 물으면 답할 수 없다.
     *
     * <p>⚠️ <b>수기 부여분은 건드리지 않는다.</b> 멱등키가 없는 행(= 사람이 준 것)은
     * 이 조회에 걸리지 않는다. 사람이 판단해 준 것을 출결 정정이 거두면 안 된다.
     *
     * @return 취소한 건수
     */
    public int revokeAuto(StudentEnrollment enrollment,
                          PenaltyTriggerType triggerType,
                          String condition,
                          LocalDate occurredOn) {
        List<PenaltyRule> rules = ruleRepository.findActive(
                enrollment.getAcademy().getId(), enrollment.getYear(), triggerType);

        int revoked = 0;
        for (PenaltyRule rule : rules) {
            if (!rule.matches(condition)) {
                continue;
            }
            String key = PenaltyPoint.attendanceKey(
                    enrollment.getId(), occurredOn, rule.getId());
            var found = pointRepository.findByIdempotencyKeyAndDeletedFalse(key);
            if (found.isPresent()) {
                found.get().markDeleted();
                revoked++;
                log.info("출결 정정으로 자동 벌점 취소: key={}, 항목={}",
                        key, rule.getPenaltyItem().getItemName());
            }
        }
        return revoked;
    }

    /**
     * ★★ 대체 대상의 <b>자동 부여분</b>을 거둔다 (디랩 1029 4-2).
     *
     * <h2>★ 거두는 대상은 자동 부여분만이다</h2>
     * 멱등키가 있는 행만 본다 — <b>수기 부여분은 건드리지 않는다.</b> 직원이 손으로
     * 넣은 벌점을 규칙이 지우면 <b>왜 사라졌는지 아무도 모른다.</b>
     *
     * <p>★ <b>같은 날</b>의 것만 거둔다. 멱등키가 {@code 학생:일자:규칙} 이라
     * 다른 날 벌점은 애초에 키가 다르다.
     *
     * <p>⚠️ 대체 대상이 <b>꺼져 있어도 거둔다</b> — 규칙을 끈 뒤에도
     * <b>이미 붙은 자동 벌점은 남아 있다.</b> 그것까지 두면 중복이 그대로다.
     */
    private void supersede(StudentEnrollment enrollment, PenaltyRule rule,
                           LocalDate occurredOn) {
        PenaltyRule target = rule.getSupersedesRule();
        if (target == null) {
            return;
        }
        String key = PenaltyPoint.attendanceKey(
                enrollment.getId(), occurredOn, target.getId());
        pointRepository.findByIdempotencyKeyAndDeletedFalse(key).ifPresent(point -> {
            point.markDeleted();
            log.info("★ 대체 규칙으로 자동 벌점 취소: key={}, 거둔항목={}, 부여규칙={}",
                    key, target.getPenaltyItem().getItemName(), rule.getId());
        });
    }

    /**
     * ★★ <b>횟수를 세어</b> 임계값에 닿으면 부여한다 (디랩 1029 4-6).
     *
     * <h2>★ 왜 따로 두는가 — 건별 트리거와 방향이 다르다</h2>
     * {@link #apply} 는 <b>한 번 일어난 것</b>에 붙는다. 이쪽은 <b>「3회째에」</b>라서
     * <b>지금까지 몇 번인지를 호출자가 알려 줘야</b> 한다.
     *
     * <p>★ <b>세는 것은 호출자가 한다</b> — 도메인마다 세는 단위가 다르다(순찰은 회차,
     * 루틴은 주간 회차). ⚠️ 엔진이 세면 <b>엔진이 모든 도메인을 알아야</b> 한다.
     *
     * <p>★ <b>임계값은 규칙 데이터</b>({@code threshold})다 — 코드에 3을 박지 않았다.
     * ⚠️ {@code threshold} 가 없는 규칙은 <b>건너뛴다</b> — 세지 않는 규칙이다.
     *
     * @param occurrences 지금까지 일어난 횟수. ★ 임계값 <b>이상</b>이면 부여한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PenaltyPoint> applyCounted(StudentEnrollment enrollment,
                                           PenaltyTriggerType triggerType,
                                           String condition,
                                           LocalDate occurredOn,
                                           int occurrences) {
        List<PenaltyRule> rules = ruleRepository.findActive(
                enrollment.getAcademy().getId(), enrollment.getYear(), triggerType);

        List<PenaltyPoint> applied = new ArrayList<>();
        for (PenaltyRule rule : rules) {
            if (!rule.matches(condition) || !rule.counts()) {
                continue;
            }
            if (occurrences < rule.getThreshold()) {
                continue;
            }
            // ★ 멱등키가 「학생:일자:규칙」이라 ★ 하루 한 번만 붙는다 —
            //   ⚠️ 4회·5회째에 또 붙지 않는다. 그게 맞다(「3회 체크하면 부여」).
            supersede(enrollment, rule, occurredOn);
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
