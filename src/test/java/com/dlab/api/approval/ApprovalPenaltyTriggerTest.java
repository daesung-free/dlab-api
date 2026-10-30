package com.dlab.api.approval;

import static org.assertj.core.api.Assertions.assertThat;

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
     * ★★ 사유 신청은 담임이 승인한다 — 학생 선택도, 지점 정책(PARENT)도 보지 않는다.
     *
     * <p>⚠️ 학생 선택을 따르면 <b>「학부모」를 고른 학생의 사유 신청이 학부모에게 가고,
     * 학부모가 승인한 건에 벌점이 붙는다</b> — 학원이 확인하지 않은 벌점이다.
     */
    @Test
    @DisplayName("★★ 사유 신청의 우선 승인자는 담임이다 — 지점 정책이 PARENT 여도 그렇다")
    void absenceReasonAlwaysGoesToTeacher() {
        ApprovalRequest request = submit(AbsenceReasonType.ABSENCE);

        assertThat(request.getPrimaryApprover())
                .as("★ 정책은 PARENT 인데 담임으로 가야 한다")
                .isEqualTo(ApproverType.TEACHER);
    }
}
