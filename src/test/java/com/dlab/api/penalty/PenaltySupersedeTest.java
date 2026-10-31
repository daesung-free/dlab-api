package com.dlab.api.penalty;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.penalty.repository.PenaltyRuleRepository;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import com.dlab.domain.penalty.service.PenaltyRuleEngine;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ★★ <b>대체 관계가 실제로 거두는가</b> (디랩 1029 4-2 · 4-3).
 *
 * <h2>⚠️ 왜 별도 클래스인가 — {@code REQUIRES_NEW} 때문이다</h2>
 * {@link PenaltyRuleEngine#apply} 는 <b>부모 트랜잭션과 분리된다</b>(상벌점 부여가
 * 실패해도 출결 기록은 남아야 하므로). 그래서 <b>테스트 트랜잭션 안에서 만든 규칙을
 * 엔진이 보지 못하고</b>, 「규칙이 없어 아무 일도 안 했다」와
 * <b>「부르지도 않았다」가 구분되지 않는다.</b>
 *
 * <p>★ 그래서 이 클래스는 <b>{@code @Transactional} 을 쓰지 않고</b>
 * {@link TransactionTemplate} 으로 <b>설정을 커밋</b>한다. ⚠️ 롤백이 없으므로
 * <b>뒷정리를 직접 한다.</b>
 */
@SpringBootTest
class PenaltySupersedeTest {

    @Autowired PenaltyRuleEngine engine;
    @Autowired PenaltyMasterService masterService;
    @Autowired PenaltyRuleRepository ruleRepository;
    @Autowired PenaltyPointRepository pointRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;
    @Autowired TransactionTemplate tx;

    short year;
    LocalDate today;
    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;
    Long autoLateRuleId;
    Long excusedLateRuleId;
    /**
     * ★ 실행마다 다른 값. ⚠️ 이 클래스는 <b>롤백이 없어</b> 잔여물이 남을 수 있고,
     * 고정 코드를 쓰면 <b>다음 실행이 유니크 제약으로 깨진다</b> — 실제로 그렇게 깨졌다.
     */
    String tag;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
        year = (short) today.getYear();

        tag = Long.toString(System.nanoTime() % 100000);

        tx.executeWithoutResult(status -> {
            academy = new Academy("P" + tag, "대체테스트", LocalTime.of(8, 20));
            em.persist(academy);

            Student student = new Student("PS-" + tag, "김대체", "010-9000-0001");
            em.persist(student);
            enrollment = new StudentEnrollment(student, academy, year, tag,
                    null, GradeType.HIGH3);
            em.persist(enrollment);
            em.flush();

            admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                    List.of(Role.SUPER_ADMIN), true);

            var autoLateItem = masterService.createItem(admin, academy.getId(), year,
                    "자동 지각(" + tag + ")", 1, PenaltyCategory.DEMERIT);
            var autoLate = masterService.createRule(admin, academy.getId(), year,
                    PenaltyTriggerType.ATTENDANCE, "A", autoLateItem.getId());
            masterService.toggleRule(admin, autoLate.getId(), true);
            autoLateRuleId = autoLate.getId();

            var excusedItem = masterService.createItem(admin, academy.getId(), year,
                    "사유 지각(" + tag + ")", 1, PenaltyCategory.DEMERIT);
            var excusedLate = masterService.createRule(admin, academy.getId(), year,
                    PenaltyTriggerType.APPROVAL, "LATE", excusedItem.getId());
            masterService.toggleRule(admin, excusedLate.getId(), true);
            excusedLateRuleId = excusedLate.getId();
            em.flush();
        });
    }

    @AfterEach
    void tearDown() {
        // ⚠️ 롤백이 없다 — 직접 치운다. 안 치우면 다음 실행에서 이름 중복으로 깨진다
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM penalty_point WHERE enrollment_id = :id")
                    .setParameter("id", enrollment.getId()).executeUpdate();
            em.createNativeQuery(
                            "DELETE FROM penalty_rule WHERE academy_id = :id")
                    .setParameter("id", academy.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM penalty_item WHERE academy_id = :id")
                    .setParameter("id", academy.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM student_enrollment WHERE id = :id")
                    .setParameter("id", enrollment.getId()).executeUpdate();
            // ⚠️ 학생도 치운다 — unique_code 가 남으면 ★ 다음 실행이 중복으로 깨진다
            em.createNativeQuery("DELETE FROM student WHERE unique_code = :code")
                    .setParameter("code", "PS-" + tag).executeUpdate();
            em.createNativeQuery("DELETE FROM academy WHERE id = :id")
                    .setParameter("id", academy.getId()).executeUpdate();
        });
    }

    /**
     * ★★ 대체 지정이 있으면 <b>먼저 거둔다</b> — 자동 지각 ↔ 사유 지각 중복 방지.
     *
     * <p>시트 원문: <b>「자동 지각(-1)이 적용된 후 … 사유지각(-1)을 입력하는 경우
     * 벌점이 중복 적용되지 않아야 합니다」</b>
     */
    @Test
    @DisplayName("★★ 대체 지정이 있으면 자동 지각을 먼저 거둔다 — 중복이 남으면 두 배로 깎인다")
    void supersedeRevokesFirst() {
        tx.executeWithoutResult(status ->
                masterService.changeSupersedes(admin, excusedLateRuleId, autoLateRuleId));

        // ① 키오스크 태깅으로 자동 지각이 붙는다
        engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", today);
        assertThat(livePoints()).hasSize(1);

        // ② 사유 지각이 승인된다 — ★ 자동 지각을 거두고 자기 것을 붙인다
        engine.apply(enrollment, PenaltyTriggerType.APPROVAL, "LATE", today);

        var live = livePoints();
        assertThat(live)
                .as("★ 한 건만 남는다 — 자동 지각은 거둬졌다")
                .hasSize(1);
        // ⚠️ penalty_item 이 LAZY 라 ★ 트랜잭션 안에서 읽는다 — 밖에서 읽으면
        //    LazyInitializationException 이 난다(이 클래스는 @Transactional 이 없다)
        String itemName = tx.execute(status -> pointRepository
                .findById(live.get(0).getId()).orElseThrow()
                .getPenaltyItem().getItemName());
        assertThat(itemName).isEqualTo("사유 지각(" + tag + ")");
    }

    /**
     * ★★ 대체 지정이 <b>없으면 둘 다 남는다</b>.
     *
     * <p>★ 이것이 <b>9-7 회신을 기다리지 않아도 되는 이유</b>다 — 「-1 을 취소할지 둘지」가
     * <b>행 하나로 갈린다.</b>
     */
    @Test
    @DisplayName("★★ 대체 지정이 없으면 둘 다 남는다 — 9-7 답이 행 하나로 갈린다")
    void withoutSupersedeBothRemain() {
        engine.apply(enrollment, PenaltyTriggerType.ATTENDANCE, "A", today);
        engine.apply(enrollment, PenaltyTriggerType.APPROVAL, "LATE", today);

        assertThat(livePoints())
                .as("★ 거둘 대상을 지정하지 않았으니 ★ 둘 다 남는다")
                .hasSize(2);
    }

    /**
     * ★★ <b>수기 부여분은 건드리지 않는다.</b>
     *
     * <p>⚠️ 직원이 손으로 넣은 벌점을 규칙이 지우면 <b>왜 사라졌는지 아무도 모른다</b>
     * (1029 4-2: 「거두는 대상은 자동 부여분(멱등키 있는 행)만」).
     */
    @Test
    @DisplayName("★★ 수기 부여분은 거두지 않는다 — 지우면 왜 사라졌는지 아무도 모른다")
    void manualPointsSurvive() {
        tx.executeWithoutResult(status ->
                masterService.changeSupersedes(admin, excusedLateRuleId, autoLateRuleId));

        // ★ 수기 부여 — 멱등키가 없다
        tx.executeWithoutResult(status -> {
            var item = ruleRepository.findById(autoLateRuleId).orElseThrow().getPenaltyItem();
            em.createNativeQuery("""
                            INSERT INTO penalty_point
                                (academy_id, enrollment_id, penalty_item_id, points,
                                 source, occurred_at, created_at, updated_at,
                                 created_by, is_deleted)
                            VALUES (:academy, :enrollment, :item, -1,
                                    'MANUAL', now(), now(), now(), 0, false)
                            """)
                    .setParameter("academy", academy.getId())
                    .setParameter("enrollment", enrollment.getId())
                    .setParameter("item", item.getId())
                    .executeUpdate();
        });

        engine.apply(enrollment, PenaltyTriggerType.APPROVAL, "LATE", today);

        assertThat(livePoints())
                .as("★ 수기 -1 과 사유 지각 -1 이 ★ 둘 다 남는다")
                .hasSize(2);
    }

    private List<com.dlab.domain.penalty.entity.PenaltyPoint> livePoints() {
        em.clear();
        return pointRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .filter(p -> p.getEnrollment().getId().equals(enrollment.getId()))
                .toList();
    }
}
