package com.dlab.api.penalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
    @Autowired com.dlab.domain.penalty.repository.PenaltyPointRepository pointRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;
    AuthPrincipal admin;
    AuthPrincipal ilsanAdmin;
    AuthPrincipal headOffice;
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
        // ★ 복사는 원본·대상 두 지점을 지정하므로 전 지점 권한자만 쓸 수 있다
        headOffice = AuthPrincipal.of(3L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);
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
    @DisplayName("0점 항목은 기록용으로 만들 수 있다")
    void zeroPointIsAllowedAsRecord() {
        assertThat(item("기록용", 0, PenaltyCategory.DEMERIT).getPointValue()).isZero();
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

    // ── 분류·설명 · 공통 항목 복사 ────────────────────────────────

    @Test
    @DisplayName("분류와 설명이 저장되고 조회된다 — 디랩 항목 56개가 분류를 갖고 온다")
    void groupAndDescriptionAreStored() {
        PenaltyItem created = masterService.createItem(admin, null, year,
                "무단 이석", 5, PenaltyCategory.DEMERIT, "면학 분위기", "자리를 비우고 돌아오지 않음");
        em.flush();

        assertThat(created.getGroupName()).isEqualTo("면학 분위기");
        assertThat(created.getDescription()).isEqualTo("자리를 비우고 돌아오지 않음");
    }

    @Test
    @DisplayName("★★ 수정에서 null 은 유지, 빈 문자열은 삭제 — 이름만 고쳐도 분류가 사라지면 안 된다")
    void nullKeepsAndBlankClears() {
        PenaltyItem created = masterService.createItem(admin, null, year,
                "지각", 2, PenaltyCategory.DEMERIT, "출결", "등원 기준시각 이후");
        em.flush();

        // ★ 옛 2-인자 경로 — 분류를 건드리지 않아야 한다
        masterService.updateItem(admin, created.getId(), "지각(수정)", 3,
                PenaltyCategory.DEMERIT);
        em.flush();
        assertThat(created.getGroupName()).as("null 은 유지다").isEqualTo("출결");

        // ★ 빈 문자열은 지운다
        masterService.updateItem(admin, created.getId(), "지각(수정)", 3,
                PenaltyCategory.DEMERIT, "", "");
        em.flush();
        assertThat(created.getGroupName()).as("빈 문자열은 삭제다").isNull();
        assertThat(created.getDescription()).isNull();
    }

    @Test
    @DisplayName("★ 공통 항목을 다른 지점에 복사한다 — 56개를 11개 지점에 손으로 넣지 않게")
    void copiesItemsToAnotherAcademy() {
        masterService.createItem(admin, bundang.getId(), year, "지각", 2,
                PenaltyCategory.DEMERIT, "출결", "설명");
        masterService.createItem(admin, bundang.getId(), year, "청소 우수", 3,
                PenaltyCategory.MERIT, "생활", null);
        em.flush();

        List<PenaltyItem> copies = masterService.copyItems(
                headOffice, bundang.getId(), ilsan.getId(), year);
        em.flush();

        assertThat(copies).hasSize(2);
        assertThat(copies).allSatisfy(c ->
                assertThat(c.getAcademy().getId()).isEqualTo(ilsan.getId()));
        // ★ 분류·설명·점수까지 따라와야 한다 — 이름만 복사하면 다시 손으로 채운다
        assertThat(copies).extracting(PenaltyItem::getGroupName)
                .containsExactlyInAnyOrder("출결", "생활");
    }

    @Test
    @DisplayName("★★ 두 번 복사해도 늘어나지 않는다 — 같은 이름은 건너뛴다")
    void copyIsIdempotentByName() {
        masterService.createItem(admin, bundang.getId(), year, "지각", 2,
                PenaltyCategory.DEMERIT, "출결", null);
        em.flush();

        masterService.copyItems(headOffice, bundang.getId(), ilsan.getId(), year);
        em.flush();
        List<PenaltyItem> second = masterService.copyItems(
                headOffice, bundang.getId(), ilsan.getId(), year);
        em.flush();

        assertThat(second).as("★ 두 번째는 아무것도 만들지 않는다").isEmpty();
        assertThat(masterService.items(ilsanAdmin, ilsan.getId(), year)).hasSize(1);
    }

    @Test
    @DisplayName("같은 지점으로는 복사하지 않는다 — 자기 항목이 두 벌이 된다")
    void copyToSameAcademyIsRejected() {
        assertThatThrownBy(() -> masterService.copyItems(
                headOffice, bundang.getId(), bundang.getId(), year))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 지점 관리자는 남의 지점으로 복사할 수 없다 — 다른 지점 항목이 섞인다")
    void branchAdminCannotCopyAcrossAcademies() {
        assertThatThrownBy(() -> masterService.copyItems(
                admin, bundang.getId(), ilsan.getId(), year))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 자동 부여 여부를 묶음으로 판정한다 — 항목마다 조회하면 56개면 57 쿼리다")
    void automaticItemIdsAreResolvedInOneQuery() {
        PenaltyItem auto = item("자동지각", 2, PenaltyCategory.DEMERIT);
        PenaltyItem manual = item("수기항목", 3, PenaltyCategory.DEMERIT);
        // ★ 규칙은 만들고 켜야 자동이다 — 기본값이 꺼짐이다
        var created = masterService.createRule(admin, null, year,
                PenaltyTriggerType.ATTENDANCE, "A", auto.getId());
        masterService.toggleRule(admin, created.getId(), true);
        em.flush();

        java.util.Set<Long> ids = masterService.automaticItemIds(List.of(auto, manual));

        assertThat(ids).containsExactly(auto.getId());
        assertThat(masterService.isAutomatic(auto))
                .as("★ 단건 판정과 같은 결과여야 한다").isTrue();
        assertThat(masterService.isAutomatic(manual)).isFalse();
    }

    @Test
    @DisplayName("빈 목록이면 쿼리하지 않는다")
    void automaticItemIdsOnEmpty() {
        assertThat(masterService.automaticItemIds(List.of())).isEmpty();
    }

    // ── 멱등 ──────────────────────────────────────────────────

    /**
     * ★★ 취소한 뒤 같은 멱등키로 다시 부여할 수 있어야 한다.
     *
     * <h2>왜 깨져 있었나</h2>
     * V1 이 만든 {@code uq_penalty_idempotency} 가 <b>취소(soft delete)된 행까지 유일성에
     * 포함</b>했다. 멱등키가 {@code 학생:일자:규칙} 이라 바뀌지 않으므로, 자동 벌점을 잘못
     * 부여해 취소하면 <b>그 학생의 그 날 그 규칙은 다시 부여할 수 없었다.</b> V4 가
     * {@code AND NOT is_deleted} 를 넣은 올바른 인덱스를 따로 만들었는데 <b>V1 것이 운영에
     * 그대로 남아 있었다</b>(2026-10-29 운영 실측으로 둘 다 확인).
     *
     * <h2>★ 인덱스를 직접 시험한다 — 엔진으로는 표현할 수 없다</h2>
     * {@code PenaltyRuleEngine.apply} 는 {@code REQUIRES_NEW} 라 <b>별도 트랜잭션에서
     * 커밋</b>한다. 그래서 테스트 트랜잭션에서 취소해도 엔진의 새 트랜잭션은 그것을 보지
     * 못하고 <b>사전 조회에서 걸러져 빈 결과가 온다</b> — 인덱스 때문이 아닌데 인덱스
     * 문제처럼 보인다(실제로 그렇게 한 번 잘못 읽었다). 그래서 <b>리포지토리로 내려가
     * 유일성만 본다.</b>
     */
    @Test
    @DisplayName("★★ 취소한 행과 같은 멱등키로 다시 넣을 수 있다 — 구 인덱스가 막고 있었다")
    void revokedRowDoesNotBlockSameIdempotencyKey() {
        PenaltyItem late = item("지각", 2, PenaltyCategory.DEMERIT);
        String key = "enr:" + minji.getId() + ":2026-03-02:rule:999";

        PenaltyPoint first = pointRepository.saveAndFlush(point(late, key));
        first.markDeleted();
        em.flush();

        assertThatCode(() -> pointRepository.saveAndFlush(point(late, key)))
                .as("★ 취소된 행이 재부여를 막지 않아야 한다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 취소하지 않은 행은 같은 키를 막는다 — 멱등 보장은 그대로다")
    void liveRowStillBlocksSameIdempotencyKey() {
        PenaltyItem late = item("지각", 2, PenaltyCategory.DEMERIT);
        String key = "enr:" + minji.getId() + ":2026-03-03:rule:998";

        pointRepository.saveAndFlush(point(late, key));

        // ★ 키오스크 재태깅·배치 재실행·다중 인스턴스를 막는 최종 방어선이다
        assertThatThrownBy(() -> pointRepository.saveAndFlush(point(late, key)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private PenaltyPoint point(PenaltyItem item, String key) {
        return new PenaltyPoint(bundang, minji, item, item.getPointValue(),
                item.getItemName(), PenaltySource.KIOSK, key);
    }
}
