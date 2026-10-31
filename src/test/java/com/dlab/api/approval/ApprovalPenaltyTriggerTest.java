package com.dlab.api.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyMasterService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * 사유 신청 <b>승인</b>에 벌점이 붙는다 (디랩 1029 답변서 4-1).
 *
 * <h2>★★ 이 트리거는 방향이 다르다</h2>
 * 나머지는 <b>학생 행위</b>(태깅·루틴)에 붙는데 이것은 <b>직원의 승인</b>에 붙는다.
 * 시트 원문이 *「직원이 사유 확인 후 승인 시」* 다.
 *
 * <h2>★ 10/21 결론을 뒤집는다</h2>
 * 그때는 *「벌점은 태깅에만 붙는다 — approval 도메인에 엔진 호출이 0건」* 이었고 디랩에도
 * 그렇게 회신했다. 이번 시트가 사유 4종을 <b>승인 시 자동</b>으로 적었다.
 */
@SpringBootTest
@Transactional
class ApprovalPenaltyTriggerTest {

    @Autowired AbsenceReasonService absenceReasonService;
    @Autowired ApprovalService approvalService;
    @Autowired PenaltyMasterService masterService;
    @Autowired com.dlab.domain.penalty.repository.PenaltyRuleRepository ruleRepository;
    @Autowired com.dlab.domain.penalty.repository.PenaltyPointRepository pointRepository;
    @Autowired com.dlab.domain.approval.repository.ApprovalRequestRepository requestRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /**
     * ★★ 엔진을 목으로 둔다 — <b>이 PR 이 추가한 것은 「부르는 쪽」이다.</b>
     *
     * <p>엔진 자체는 {@code PenaltyMasterTest} 가 본다. 그리고 {@code apply} 가
     * {@code REQUIRES_NEW} 라 <b>별도 트랜잭션에서 돈다</b> — 테스트 트랜잭션에서 만든
     * 규칙을 보지 못해 <b>실제로 부여되지 않고</b>, 그러면 「부르지도 않았다」와
     * <b>구분되지 않는다</b>(오늘 그렇게 한 번 잘못 읽었다).
     * {@code PenaltyTriggerWiringTest} 가 같은 이유로 같은 방식이다.
     */
    @MockitoBean
    com.dlab.domain.penalty.service.PenaltyRuleEngine penaltyRuleEngine;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    Long teacherAccountId;
    LocalDate today;
    short year = 2026;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(bundang);

        // ★ 일부러 PARENT 로 둔다 — 사유 신청은 이 정책을 무시하고 담임으로 가야 한다
        em.persist(new ApprovalItem(bundang, year,
                RequestType.ABSENCE_REASON, ApproverType.PARENT, null, null));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, year, "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);

        Employee staff = new Employee(bundang, "행정쌤");
        em.persist(staff);
        Account account = Account.forEmployee(staff, "APSTAFF", "x", false);
        em.persist(account);
        em.flush();
        teacherAccountId = account.getId();

        admin = AuthPrincipal.of(account.getId(), "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    /** 사유 유형에 벌점 규칙을 켠다. ★ 규칙이 없으면 아무 일도 안 일어난다. */
    private PenaltyItem ruleFor(AbsenceReasonType type, String itemName, int point) {
        PenaltyItem item = masterService.createItem(admin, bundang.getId(), year,
                itemName, point, PenaltyCategory.DEMERIT);
        var rule = masterService.createRule(admin, bundang.getId(), year,
                PenaltyTriggerType.APPROVAL, type.name(), item.getId());
        masterService.toggleRule(admin, rule.getId(), true);
        em.flush();
        return item;
    }

    private ApprovalRequest submit(AbsenceReasonType type) {
        absenceReasonService.register(admin, minji.getId(), today, type, "병원",
                type == AbsenceReasonType.OUTING ? LocalTime.of(13, 0) : null,
                type == AbsenceReasonType.OUTING ? LocalTime.of(15, 0) : null, null);
        em.flush();
        em.clear();
        return requestRepository.findAll().stream()
                .filter(r -> r.getEnrollment().getId().equals(minji.getId()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★★ 사유 신청이 승인되면 벌점이 붙는다 — 그동안 승인에는 아무것도 안 붙었다")
    void approvalGrantsPenalty() {
        ruleFor(AbsenceReasonType.ABSENCE, "사유 결석", 2);
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        approvalService.approve(request.getId(), teacherAccountId);
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.argThat(e -> e.getId().equals(minji.getId())),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("ABSENCE"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /**
     * ★★ 멱등키의 날짜가 <b>신청 대상 날짜</b>다 — 승인한 날이 아니다.
     *
     * <p>승인일로 두면 키가 달라져 <b>같은 결석에 두 번 붙을 수 있다</b> — 철회하고 다시
     * 승인하거나, 신청을 두 번 올리면 그렇게 된다.
     *
     * <h2>⚠️⚠️ 집계는 아직 「부여 시각」으로 센다 — 이 PR 범위 밖의 결함이다</h2>
     * {@code PenaltyPoint} 에 <b>발생일 컬럼이 없다.</b> {@code occurred_at} 은
     * {@code Instant.now()} 이고 기간 조회가 그것을 쓴다. 그래서 <b>3월 2일 결석을
     * 3월 5일에 승인하면 「3월 5일 벌점」으로 집계된다.</b>
     *
     * <p>★ 기존 트리거(태깅·루틴)는 <b>당일 처리</b>라 드러나지 않았고, <b>승인 트리거에서
     * 처음 드러난다.</b> 고치려면 컬럼을 더하거나 {@code occurred_at} 에 발생일을 넣어야
     * 하는데, <b>기존 집계의 뜻이 바뀌는 변경</b>이라 따로 올린다.
     */
    @Test
    @DisplayName("★★ 멱등키 날짜가 신청 대상 날짜다 — 같은 결석에 두 번 붙지 않게")
    void penaltyKeyUsesAttendanceDate() {
        ruleFor(AbsenceReasonType.LATE, "사유 지각", 1);
        ApprovalRequest request = submit(AbsenceReasonType.LATE);

        approvalService.approve(request.getId(), teacherAccountId);
        em.flush();

        // ★ 발생일 인자가 신청 대상 날짜여야 한다 — 멱등키가 이 값으로 만들어진다
        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("LATE"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    @Test
    @DisplayName("★ 규칙이 꺼져 있으면 아무 일도 안 일어난다 — 디랩 항목표 전에는 조용하다")
    void nothingHappensWithoutRule() {
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        approvalService.approve(request.getId(), teacherAccountId);
        em.flush();

        // ★ 규칙이 없어도 엔진은 불린다 — 규칙 유무 판단은 엔진이 한다.
        //   부르는 쪽에서 미리 걸러면 ★ 판단이 두 곳에 생긴다
        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("ABSENCE"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    @Test
    @DisplayName("★★ 반려되면 붙지 않는다 — 사유가 인정되지 않았다")
    void rejectionGrantsNothing() {
        ruleFor(AbsenceReasonType.ABSENCE, "사유 결석", 2);
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        approvalService.reject(request.getId(), teacherAccountId, "사유 불충분");
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine, org.mockito.Mockito.never())
                .apply(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        // ★ 그리고 거두는 쪽은 불린다 — 승인 직후 반려가 들어오는 경합을 막는다
        org.mockito.Mockito.verify(penaltyRuleEngine).revokeAuto(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("ABSENCE"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /**
     * ★★ 승인을 철회하면 거둔다.
     *
     * <p>이게 없으면 <b>「승인이 철회됐는데 벌점은 남았다」</b>가 되고, 학생에게 그걸
     * 설명할 방법이 없다.
     */
    @Test
    @DisplayName("★★ 승인을 철회하면 벌점을 거둔다 — 남으면 설명할 방법이 없다")
    void revokeRemovesPenalty() {
        ruleFor(AbsenceReasonType.ABSENCE, "사유 결석", 2);
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        approvalService.approve(request.getId(), teacherAccountId);
        em.flush();

        approvalService.revoke(request.getId(), admin, "착오 승인");
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).revokeAuto(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("ABSENCE"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /**
     * ★★ 사유 신청의 승인자는 <b>지점 정책·학생 선택</b>을 따른다 (2026-10-30 되돌림).
     *
     * <p>10/29 에 「담임 고정」으로 바꿨다가 되돌렸다 — 디랩 <b>입학동의서 최종본(261029)</b>
     * 9-4 가 *「정기 사유신청과 비정기 사유신청 <b>모두 학부모님께 확인 절차를 거친 후</b>
     * 신청이 허용됩니다」* 다. <b>디랩 문서가 정본이고 우리 PM 판단보다 위다.</b>
     *
     * <p>★ 「학부모가 승인한 건에 벌점이 붙는다」는 걱정은 같은 동의서가 푼다 — 9항이
     * *「학원이 <b>벌점부여</b> 및 엄격한 징계 절차를 적용하는 것에 대하여 <b>동의</b>합니다」* 다.
     * 학부모가 확인하고, 그 확인에 벌점이 붙는 것에 <b>학부모가 이미 동의한 구조</b>다.
     *
     * <p>⚠️ <b>벌점이 승인에 붙는 것 자체는 그대로다</b>(답변서 4-1) — 바뀐 것은
     * <b>누가 승인하는가</b>뿐이다.
     */
    @Test
    @DisplayName("★★ 사유 신청 승인자는 지점 정책을 따른다 — 동의서 9-4 가 「학부모 확인」이다")
    void absenceReasonFollowsPolicy() {
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        assertThat(request.getPrimaryApprover())
                .as("★ 지점 정책이 PARENT 이므로 학부모다")
                .isEqualTo(ApproverType.PARENT);
    }

    // ── 후속 처리 배선 (증빙 확인 · 미진행 · 디랩 1029 4-3) ────────
    //
    // ★★ 이 클래스는 ★ 엔진이 목이다 — 결과(벌점 행)는 볼 수 없다.
    //    실제 거두기는 ★ PenaltySupersedeTest 가 ★ 커밋된 규칙으로 검증한다.
    //    ⚠️ 엔진이 REQUIRES_NEW 라 ★ 테스트 트랜잭션의 규칙을 못 본다.

    /**
     * ★★ 증빙 확인이 ★ {@code EVIDENCE_CONFIRMED} 로 엔진을 부른다 (1029 4-3 #36).
     *
     * <p>⚠️ 조건값이 어긋나면 <b>규칙이 영영 안 걸리고</b>, 화면에는
     * <b>「체크했는데 벌점이 그대로」</b>로만 보인다.
     */
    @Test
    @DisplayName("★★ 증빙 확인이 EVIDENCE_CONFIRMED 로 엔진을 부른다")
    void evidenceConfirmCallsEngine() {
        var request = submit(AbsenceReasonType.OUTING);
        var reason = absenceReasonService.findByApproval(request.getId());

        absenceReasonService.confirmEvidence(admin, reason.getId());
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("EVIDENCE_CONFIRMED"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /** ★★ 미진행이 {@code NOT_EXECUTED} 로 엔진을 부른다 (1029 4-3 #10). */
    @Test
    @DisplayName("★★ 미진행이 NOT_EXECUTED 로 엔진을 부른다")
    void notExecutedCallsEngine() {
        var request = submit(AbsenceReasonType.OUTING);
        var reason = absenceReasonService.findByApproval(request.getId());

        absenceReasonService.markNotExecuted(admin, reason.getId());
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("NOT_EXECUTED"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /** ★ 두 번 누르면 거부한다 — 「몇 번 확인했나」가 의미를 잃는다. */
    @Test
    @DisplayName("★ 증빙 확인은 두 번 누를 수 없다")
    void evidenceConfirmIsOnce() {
        var request = submit(AbsenceReasonType.OUTING);
        var reason = absenceReasonService.findByApproval(request.getId());
        absenceReasonService.confirmEvidence(admin, reason.getId());
        em.flush();

        assertThatThrownBy(() -> absenceReasonService.confirmEvidence(admin, reason.getId()))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class);
    }

    // ── 더프 조퇴 (디랩 1029 4-4) ─────────────────────────────────

    /**
     * ★★ 더프 조퇴는 <b>응시일에만</b> 신청할 수 있다.
     *
     * <p>⚠️ 막지 않으면 <b>아무 날에나 더프 조퇴를 걸어 벌점을 낮출 수 있다</b> —
     * 일반 사유 조퇴보다 가볍게 매겨질 수 있는 항목이다.
     */
    @Test
    @DisplayName("★★ 응시일이 아니면 더프 조퇴를 못 낸다 — 아무 날에나 걸어 벌점을 낮출 수 있다")
    void dufNeedsExamDate() {
        assertThatThrownBy(() -> absenceReasonService.register(admin, minji.getId(), today,
                AbsenceReasonType.DUF_EARLY_LEAVE, "더프 응시",
                null, null, null))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class)
                .hasMessageContaining("응시일이 아닙니다");
    }

    /** ★ 응시일을 등록하면 신청된다. */
    @Test
    @DisplayName("★ 응시일을 등록하면 더프 조퇴가 신청된다")
    void dufWorksOnExamDate() {
        dufExamDate(today);

        var reason = absenceReasonService.register(admin, minji.getId(), today,
                AbsenceReasonType.DUF_EARLY_LEAVE, "더프 응시", null, null, null);

        assertThat(reason.getReasonType()).isEqualTo(AbsenceReasonType.DUF_EARLY_LEAVE);
    }

    /**
     * ★★ 전일 신청이면 <b>{@code DUF_PREV_DAY}</b> 로 걸린다 (시트 #13, -1).
     *
     * <p>★ 판정 기준은 <b>신청 생성 시각</b>이다 — ⚠️ <b>승인 시각이 아니다.</b>
     * 직원이 늦게 승인했다고 <b>학생이 당일 신청한 것으로 바뀌면 안 된다.</b>
     */
    @Test
    @DisplayName("★★ 전일 신청은 DUF_PREV_DAY 다 — 승인 시각이 아니라 신청 시각으로 가른다")
    void dufPrevDayCondition() {
        var examDay = today.plusDays(1);
        dufExamDate(examDay);

        var reason = absenceReasonService.register(admin, minji.getId(), examDay,
                AbsenceReasonType.DUF_EARLY_LEAVE, "더프 응시", null, null, null);
        em.flush();

        approvalService.approve(reason.getApprovalRequest().getId(), teacherAccountId);
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("DUF_PREV_DAY"),
                org.mockito.ArgumentMatchers.eq(examDay));
    }

    /** ★★ 당일 신청이면 {@code DUF_SAME_DAY} 다 (시트 #14, -2 — 전일보다 무겁다). */
    @Test
    @DisplayName("★★ 당일 신청은 DUF_SAME_DAY 다 — 전일보다 무겁다")
    void dufSameDayCondition() {
        dufExamDate(today);

        var reason = absenceReasonService.register(admin, minji.getId(), today,
                AbsenceReasonType.DUF_EARLY_LEAVE, "더프 응시", null, null, null);
        em.flush();

        approvalService.approve(reason.getApprovalRequest().getId(), teacherAccountId);
        em.flush();

        org.mockito.Mockito.verify(penaltyRuleEngine).apply(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(PenaltyTriggerType.APPROVAL),
                org.mockito.ArgumentMatchers.eq("DUF_SAME_DAY"),
                org.mockito.ArgumentMatchers.eq(today));
    }

    /**
     * ★ <b>전 지점 공통 응시일</b>도 걸린다.
     *
     * <p>⚠️ 공통을 안 보면 <b>전국 일정이 아무 지점에서도 안 걸린다.</b>
     */
    @Test
    @DisplayName("★ 전 지점 공통 응시일도 걸린다 — 안 보면 전국 일정이 안 걸린다")
    void commonExamDateApplies() {
        em.persist(new com.dlab.domain.attendance.entity.DufExamDate(
                null, year, today, "전국 더프"));
        em.flush();

        var reason = absenceReasonService.register(admin, minji.getId(), today,
                AbsenceReasonType.DUF_EARLY_LEAVE, "더프 응시", null, null, null);

        assertThat(reason).isNotNull();
    }

    /** 응시일을 그 지점에 등록한다. */
    private void dufExamDate(java.time.LocalDate date) {
        em.persist(new com.dlab.domain.attendance.entity.DufExamDate(
                bundang, year, date, "더프"));
        em.flush();
    }
}
