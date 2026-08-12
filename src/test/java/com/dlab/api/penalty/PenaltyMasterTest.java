package com.dlab.api.penalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상벌점 항목·규칙 관리 (F-4.1-3, I-5).
 *
 * <p>클라이언트가 "점수 생성 페이지에서 직접 입력"으로 답을 줬다 — 항목과 규칙을 화면에서
 * 만들 수 있어야 하고, <b>규칙까지 켜야 자동부여가 돈다.</b>
 */
@SpringBootTest
@Transactional
class PenaltyMasterTest {

    @Autowired PenaltyMasterService masterService;
    @Autowired com.dlab.domain.penalty.repository.PenaltyRuleRepository ruleRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;
    AuthPrincipal admin;
    AuthPrincipal ilsanAdmin;
    short year = 2026;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, year, "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private PenaltyItem item(String name, int point, PenaltyCategory category) {
        PenaltyItem created = masterService.createItem(admin, null, year, name, point, category);
        em.flush();
        return created;
    }

    // ── 항목 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 벌점을 양수로 넣어도 음수로 저장된다 — 통계가 부호로 상점·벌점을 가른다")
    void demeritIsStoredNegative() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);

        assertThat(late.getPointValue()).isEqualTo(-5);
    }

    @Test
    @DisplayName("★ 상점을 음수로 넣어도 양수로 저장된다")
    void meritIsStoredPositive() {
        PenaltyItem help = item("봉사", -3, PenaltyCategory.MERIT);

        assertThat(help.getPointValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("0점 항목은 만들 수 없다 — 부여해도 합계가 안 바뀐다")
    void zeroPointIsRejected() {
        assertThatThrownBy(() -> item("의미없음", 0, PenaltyCategory.DEMERIT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 이름 항목은 두 번 만들 수 없다")
    void duplicateNameIsRejected() {
        item("지각", 5, PenaltyCategory.DEMERIT);

        assertThatThrownBy(() -> item("지각", 3, PenaltyCategory.DEMERIT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 항목 점수를 바꿔도 이미 부여된 상벌점은 안 바뀐다 — 부여 시점 점수를 복사해둔다")
    void changingItemDoesNotAffectGrantedPoints() {
        PenaltyItem late = item("지각", 3, PenaltyCategory.DEMERIT);
        PenaltyPoint granted = new PenaltyPoint(bundang, minji, late, late.getPointValue(),
                "지각", com.dlab.domain.penalty.entity.PenaltySource.MANUAL, null);
        em.persist(granted);
        em.flush();

        masterService.updateItem(admin, late.getId(), "지각", 10, PenaltyCategory.DEMERIT);
        em.flush();
        em.clear();

        assertThat(em.find(PenaltyPoint.class, granted.getId()).getPoints()).isEqualTo(-3);
        assertThat(em.find(PenaltyItem.class, late.getId()).getPointValue()).isEqualTo(-10);
    }

    @Test
    @DisplayName("★ 규칙이 걸린 항목은 삭제할 수 없다 — 지우면 규칙이 없는 항목을 가리킨다")
    void itemWithRuleCannotBeDeleted() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        masterService.createRule(admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        em.flush();

        assertThatThrownBy(() -> masterService.deleteItem(admin, late.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("규칙");
    }

    @Test
    @DisplayName("★ 삭제한 이름을 다시 쓸 수 있다 — soft delete가 유니크를 잡고 있으면 안 된다")
    void deletedNameCanBeReused() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        masterService.deleteItem(admin, late.getId());
        em.flush();

        assertThat(item("지각", 3, PenaltyCategory.DEMERIT).getPointValue()).isEqualTo(-3);
    }

    // ── 규칙 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 규칙은 기본이 꺼짐이다 — 만들자마자 돌면 검증 전 규칙이 전교생에게 벌점을 뿌린다")
    void ruleIsInactiveByDefault() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        PenaltyRule rule = masterService.createRule(
                admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        em.flush();

        assertThat(rule.isActive()).isFalse();
        // 엔진은 활성 규칙만 읽는다 — 꺼져 있으면 조회 자체에서 빠진다
        assertThat(ruleRepository.findActive(bundang.getId(), year, PenaltyTriggerType.ATTENDANCE))
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 규칙을 켜야 자동부여가 돈다 — 항목만 만들면 수기 부여만 된다")
    void activatedRuleGrantsPoints() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        PenaltyRule rule = masterService.createRule(
                admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        masterService.toggleRule(admin, rule.getId(), true);
        em.flush();

        var active = ruleRepository.findActive(bundang.getId(), year, PenaltyTriggerType.ATTENDANCE);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).matches("A")).isTrue();
        assertThat(active.get(0).getPenaltyItem().getPointValue()).isEqualTo(-5);
    }

    @Test
    @DisplayName("★ 조건이 다르면 안 걸린다 — 지각 규칙이 결석에 붙으면 안 된다")
    void conditionMustMatch() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        PenaltyRule rule = masterService.createRule(
                admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        masterService.toggleRule(admin, rule.getId(), true);
        em.flush();

        assertThat(ruleRepository.findActive(bundang.getId(), year, PenaltyTriggerType.ATTENDANCE))
                .allSatisfy(r -> assertThat(r.matches("T")).isFalse());
    }

    @Test
    @DisplayName("규칙을 끄면 다시 안 돈다 — 지우지 않고 끄면 과거 부여 근거가 남는다")
    void deactivatedRuleStopsGranting() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        PenaltyRule rule = masterService.createRule(
                admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        masterService.toggleRule(admin, rule.getId(), true);
        masterService.toggleRule(admin, rule.getId(), false);
        em.flush();

        assertThat(ruleRepository.findActive(bundang.getId(), year, PenaltyTriggerType.ATTENDANCE))
                .isEmpty();
    }

    @Test
    @DisplayName("꺼진 규칙도 목록에는 나온다 — 화면이 토글을 그려야 한다")
    void inactiveRuleIsStillListed() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);
        masterService.createRule(admin, null, year, PenaltyTriggerType.ATTENDANCE, "A", late.getId());
        em.flush();
        em.clear();

        assertThat(masterService.rules(admin, null, year)).hasSize(1);
    }

    @Test
    @DisplayName("정기일정 미인정도 트리거로 쓸 수 있다")
    void regularScheduleTriggerWorks() {
        PenaltyItem miss = item("정기일정 미인정", 3, PenaltyCategory.DEMERIT);
        PenaltyRule rule = masterService.createRule(admin, null, year,
                PenaltyTriggerType.REGULAR_SCHEDULE, "NOT_RECOGNIZED", miss.getId());
        masterService.toggleRule(admin, rule.getId(), true);
        em.flush();

        assertThat(ruleRepository.findActive(bundang.getId(), year,
                PenaltyTriggerType.REGULAR_SCHEDULE))
                .singleElement()
                .satisfies(r -> assertThat(r.matches("NOT_RECOGNIZED")).isTrue());
    }

    // ── 스코프 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 다른 지점 항목은 건드릴 수 없다")
    void otherBranchItemIsProtected() {
        PenaltyItem late = item("지각", 5, PenaltyCategory.DEMERIT);

        assertThatThrownBy(() -> masterService.updateItem(
                ilsanAdmin, late.getId(), "지각", 1, PenaltyCategory.DEMERIT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("항목은 지점별로 따로다")
    void itemsAreScopedByBranch() {
        item("지각", 5, PenaltyCategory.DEMERIT);
        em.flush();
        em.clear();

        assertThat(masterService.items(admin, null, year)).hasSize(1);
        assertThat(masterService.items(ilsanAdmin, null, year)).isEmpty();
    }
}
