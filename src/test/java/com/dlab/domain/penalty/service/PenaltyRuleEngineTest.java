package com.dlab.domain.penalty.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.penalty.repository.PenaltyRuleRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 자동부여 엔진. <b>규칙이 데이터로 들어온다는 것</b>과 <b>중복이 막힌다는 것</b>이 핵심이다.
 * 후자가 깨지면 학생이 카드를 두 번 찍었다고 벌점이 두 배가 된다.
 */
class PenaltyRuleEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 4);

    private PenaltyRuleRepository ruleRepository;
    private PenaltyPointRepository pointRepository;
    private PenaltyRuleEngine engine;

    private Academy academy;
    private StudentEnrollment enrollment;
    private PenaltyItem lateItem;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(PenaltyRuleRepository.class);
        pointRepository = mock(PenaltyPointRepository.class);
        engine = new PenaltyRuleEngine(ruleRepository, pointRepository);

        academy = new Academy("31", "분당", java.time.LocalTime.of(9, 0));
        ReflectionTestUtils.setField(academy, "id", 7L);

        enrollment = mock(StudentEnrollment.class);
        given(enrollment.getId()).willReturn(100L);
        given(enrollment.getAcademy()).willReturn(academy);
        given(enrollment.getYear()).willReturn((short) 2026);

        lateItem = new PenaltyItem(academy, (short) 2026, "지각", -2, PenaltyCategory.DEMERIT);

        given(pointRepository.existsByIdempotencyKeyAndDeletedFalse(anyString())).willReturn(false);
        given(pointRepository.saveAndFlush(any(PenaltyPoint.class)))
                .willAnswer(inv -> inv.getArgument(0));
    }

    private PenaltyRule activeRule(String condition, PenaltyItem item, Long id) {
        PenaltyRule rule = new PenaltyRule(
                academy, (short) 2026, PenaltyTriggerType.ATTENDANCE, condition, item);
        rule.activate();
        ReflectionTestUtils.setField(rule, "id", id);
        return rule;
    }

    @Test
    @DisplayName("규칙이 없으면 아무 일도 안 일어난다 — I-5 미확정 상태의 기본 동작")
    void noRuleNoEffect() {
        given(ruleRepository.findActive(any(), any(Short.class), any())).willReturn(List.of());

        var applied = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);

        assertThat(applied).isEmpty();
        verify(pointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("조건이 맞는 규칙만 적용된다")
    void appliesMatchingRuleOnly() {
        given(ruleRepository.findActive(any(), any(Short.class), any())).willReturn(List.of(
                activeRule("A", lateItem, 1L),                                   // 지각
                activeRule("C", new PenaltyItem(academy, (short) 2026, "조퇴", -3,
                        PenaltyCategory.DEMERIT), 2L)));                          // 조퇴

        var applied = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).getPenaltyItem().getItemName()).isEqualTo("지각");
    }

    @Test
    @DisplayName("★ 점수는 항목 값을 복사한다 — 항목이 나중에 바뀌어도 과거 이력은 그대로여야 한다")
    void copiesPointValueFromItem() {
        given(ruleRepository.findActive(any(), any(Short.class), any()))
                .willReturn(List.of(activeRule("A", lateItem, 1L)));

        var applied = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);

        assertThat(applied.get(0).getPoints()).isEqualTo(-2);
    }

    @Test
    @DisplayName("★ 같은 날 같은 규칙은 한 번만 — 카드를 두 번 찍어도 벌점은 한 번")
    void idempotentWithinSameDay() {
        given(ruleRepository.findActive(any(), any(Short.class), any()))
                .willReturn(List.of(activeRule("A", lateItem, 1L)));
        given(pointRepository.existsByIdempotencyKeyAndDeletedFalse(anyString())).willReturn(true);

        var applied = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);

        assertThat(applied).isEmpty();
        verify(pointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("★ 동시 실행으로 사전 확인을 통과해도 DB 제약이 막는다")
    void concurrentInsertBlockedByConstraint() {
        given(ruleRepository.findActive(any(), any(Short.class), any()))
                .willReturn(List.of(activeRule("A", lateItem, 1L)));
        // 두 인스턴스가 동시에 "없음"으로 판단한 뒤 둘 다 INSERT하는 상황
        given(pointRepository.saveAndFlush(any(PenaltyPoint.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key"));

        var applied = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);

        // 예외가 밖으로 나가지 않는다 — 출결 기록까지 롤백되면 안 된다
        assertThat(applied).isEmpty();
    }

    @Test
    @DisplayName("멱등키에 학생·일자·규칙이 모두 들어간다 — 하나라도 빠지면 다른 건까지 막힌다")
    void idempotencyKeyIncludesAllDimensions() {
        String key = PenaltyPoint.attendanceKey(100L, DAY, 1L);

        assertThat(key).contains("100").contains("2026-08-04").contains("1");
        assertThat(PenaltyPoint.attendanceKey(100L, DAY, 2L)).isNotEqualTo(key);
        assertThat(PenaltyPoint.attendanceKey(100L, DAY.plusDays(1), 1L)).isNotEqualTo(key);
        assertThat(PenaltyPoint.attendanceKey(101L, DAY, 1L)).isNotEqualTo(key);
    }

    @Test
    @DisplayName("트리거 종류에 따라 출처가 갈린다")
    void sourceDependsOnTrigger() {
        given(ruleRepository.findActive(any(), any(Short.class), any()))
                .willReturn(List.of(activeRule("A", lateItem, 1L)));

        var attendance = engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", DAY);
        assertThat(attendance.get(0).getSource()).isEqualTo(PenaltySource.KIOSK);
    }

    @Test
    @DisplayName("규칙은 기본이 비활성이다 — 넣어도 켜기 전엔 안 돈다")
    void ruleIsInactiveByDefault() {
        PenaltyRule rule = new PenaltyRule(
                academy, (short) 2026, PenaltyTriggerType.ATTENDANCE, "A", lateItem);

        assertThat(rule.isActive()).isFalse();
        rule.activate();
        assertThat(rule.isActive()).isTrue();
    }
}
