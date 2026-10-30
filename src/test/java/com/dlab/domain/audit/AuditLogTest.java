package com.dlab.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 감사 로그(F-C-1)가 <b>실제로 남는지</b>.
 *
 * <p><b>왜 테스트로 못박나</b> — 이건 조용히 안 남는 것이 최악이다. 기록이 빠져도
 * 화면은 정상으로 보이고, 필요해진 시점(보안 심사·분쟁)에야 비어 있는 걸 알게 되는데
 * <b>그때는 지난 기간을 복구할 수 없다</b>.
 *
 * <p>리스너 등록이 풀리거나 {@code @Audited}가 떨어져도 컴파일은 통과하므로
 * 여기서 잡는다.
 */
@SpringBootTest
class AuditLogTest {

    @Autowired NoticeService noticeService;
    @Autowired com.dlab.api.admin.audit.AdminAuditLogController auditLogController;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TransactionTemplate tx;
    @Autowired com.dlab.domain.user.repository.AcademyRepository academyRepository;
    @Autowired com.dlab.domain.user.repository.AccountRepository accountRepository;
    @Autowired com.dlab.domain.user.repository.EmployeeRepository employeeRepository;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    AuthPrincipal admin;
    Academy academy;

    /**
     * 트랜잭션 테스트가 아니라 <b>데이터가 커밋되어 남는다</b> — 지점 코드가 겹치면
     * 유니크 제약에 걸린다. 실행 간에도 남으므로 매번 다른 값이어야 한다.
     *
     * <p>⚠️ <b>{@code nanoTime() % 100_000}으로 만들던 자리다.</b> 5자리라 경우의 수가
     * 10만인데 실행할 때마다 행이 쌓이므로, 돌릴수록 충돌 확률이 올라간다. 실제로
     * 전체 테스트에서 무작위로 깨졌다(그때 남아 있던 행이 23개). 무작위 실패는
     * 원인을 찾는 데 드는 시간이 실패 자체보다 비싸다.
     *
     * <p>{@code acad_cd}가 20자라 UUID 8자리면 충분히 넓다.
     */
    private static String uniqueCode() {
        return "A" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @BeforeEach
    void setUp() {
        String code = uniqueCode();
        academy = tx.execute(status ->
                academyRepository.save(new Academy(code, "감사테스트", LocalTime.of(9, 0))));

        Long accountId = tx.execute(status -> {
            Employee employee = employeeRepository.save(new Employee(academy, "감사행정"));
            return accountRepository.save(
                    Account.forEmployee(employee, "audit" + code, "x", false)).getId();
        });

        admin = AuthPrincipal.of(accountId, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @Test
    @DisplayName("★★ 공지를 만들면 감사 로그가 남는다 — 리스너가 떨어져도 컴파일은 통과한다")
    void createIsRecorded() {
        Notice notice = createNotice();

        List<AuditLog> logs = auditLogRepository.findByEntity("공지", notice.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(logs.get(0).getActorId()).isEqualTo(admin.accountId());
    }

    @Test
    @DisplayName("★★ 누가 했는지를 사람 이름으로 되짚을 수 있다 — 저장값은 계정 유형이었다")
    void actorNameIsResolvedFromAccount() {
        // 저장 시점에는 계정 유형(EMPLOYEE)이 들어간다. 사람 이름을 저장하려면
        // 리스너가 플러시 중일 때 조회해야 하는데, 그러면 ConcurrentModificationException 으로
        // 커밋이 통째로 깨진다 — 실제로 그렇게 만들었다가 되돌렸다.
        // 그래서 이름은 조회 시점에 붙인다. 그 근거가 되는 쿼리를 고정한다.
        Notice notice = createNotice();

        List<AuditLog> logs = auditLogRepository.findByEntity("공지", notice.getId());
        assertThat(logs).isNotEmpty();

        Long actorId = logs.get(0).getActorId();
        assertThat(accountRepository.findActorNames(List.of(actorId)))
                .anySatisfy(row -> {
                    assertThat(row[0]).isEqualTo(actorId);
                    assertThat(row[1]).isEqualTo("감사행정");
                });
    }

    @Test
    @DisplayName("★ soft delete 는 UPDATE 가 아니라 DELETE 로 남는다 — 화면이 삭제를 구분해야 한다")
    void softDeleteIsRecordedAsDelete() {
        Notice notice = createNotice();
        tx.executeWithoutResult(status -> noticeService.delete(admin, notice.getId()));

        assertThat(auditLogRepository.findByEntity("공지", notice.getId()))
                .extracting(AuditLog::getAction)
                .contains(AuditAction.DELETE);
    }

    @Test
    @DisplayName("수정하면 UPDATE 로 남는다")
    void updateIsRecorded() {
        Notice notice = createNotice();
        tx.executeWithoutResult(status ->
                noticeService.patch(admin, notice.getId(), null, null, true, null, null, null));

        assertThat(auditLogRepository.findByEntity("공지", notice.getId()))
                .extracting(AuditLog::getAction)
                .contains(AuditAction.UPDATE);
    }

    /**
     * <b>트랜잭션을 열어 커밋까지 간다.</b> {@code @Transactional} 테스트로 두면
     * {@code REQUIRES_NEW}로 분리된 감사 기록이 <b>바깥 트랜잭션과 다른 커넥션</b>에서
     * 돌아 서로를 못 본다 — 실제 동작은 되는데 테스트만 실패한다.
     */
    private Notice createNotice() {
        return tx.execute(status ->
                noticeService.createForBranch(admin, academy.getId(), "제목", "본문"));
    }

    // ─────────────────────────────────────────────────────────────
    // 조회 기간 (디랩 1021 피드백 4-3)
    // ─────────────────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.DisplayName("★ 1년까지는 조회된다 — 디랩이 「적어도 당해」를 요청했다")
    void oneYearIsQueryable() {
        var today = java.time.LocalDate.now();

        org.assertj.core.api.Assertions.assertThatCode(() ->
                auditLogController.search(admin, null,
                        today.minusYears(1).plusDays(1), today,
                        null, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * ★ 조회 기간은 <b>설정값</b>이다(기본 3년 — 디랩 확정, 2026-10-30).
     *
     * <p>전에는 코드에 1 이 박혀 있었고 이 테스트가 「1년」을 단정했다. ★ 그래서
     * <b>설정을 바꿀 때 이 테스트가 걸렸다</b> — 제 역할을 한 것이다.
     *
     * <p>★ 이제 <b>숫자를 단정하지 않는다.</b> 지키려는 것은 「상한이 있다」이고,
     * 그 값이 몇 년인지는 <b>설정이 정한다</b> — 숫자를 박으면 설정을 바꿀 때마다
     * 테스트도 고쳐야 하고, 그러면 테스트가 <b>설정을 따라 적는 종이</b>가 된다.
     */
    @org.junit.jupiter.api.DisplayName("★★ 기간을 넘는 조회는 막는다 — from=2000-01-01 한 줄로 전 기간을 훑는다")
    void beyondRetentionWindowIsRejected() {
        var today = java.time.LocalDate.now();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                auditLogController.search(admin, null,
                        java.time.LocalDate.of(2000, 1, 1), today,
                        null, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class)
                .hasMessageContaining("까지 조회할 수 있습니다");
    }

    /**
     * ★★ 설정 기간 안이면 조회된다 — <b>상한이 너무 좁으면 쓸 수 없다.</b>
     *
     * <p>1년으로 박혀 있던 동안 <b>1년 넘은 이력이 화면에서 안 보였다</b>(데이터는 남아
     * 있었다). 3년으로 올렸으니 2년 전도 조회돼야 한다.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("★★ 2년 전은 조회된다 — 1년으로 박혀 있던 동안 안 보였다")
    void withinRetentionWindowIsAllowed() {
        var today = java.time.LocalDate.now();

        org.assertj.core.api.Assertions.assertThatCode(() ->
                auditLogController.search(admin, null,
                        today.minusYears(2), today,
                        null, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .doesNotThrowAnyException();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("종료일이 시작일보다 빠르면 막는다 — 0건을 「기록이 없다」로 읽는다")
    void reversedRangeIsRejected() {
        var today = java.time.LocalDate.now();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                auditLogController.search(admin, null, today, today.minusDays(3),
                        null, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class)
                .hasMessageContaining("종료일");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("★★ 1년이 지난 행을 지우지 않는다 — 감사 로그는 분쟁의 근거다")
    void oldRowsAreNotDeleted() throws Exception {
        // 삭제·보관 배치가 없어야 한다. 있으면 조회 창(1년)과 섞여
        // "조회가 안 되는 것"과 "지워진 것"이 구분되지 않는다.
        // ⚠️ 1년 보관이 개인정보 처리방침과 맞는지는 회신 대기 항목이다(Q5).
        var scanner = new org.springframework.context.annotation
                .ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                org.springframework.stereotype.Component.class));

        var deleters = new java.util.ArrayList<String>();
        for (var c : scanner.findCandidateComponents("com.dlab.domain.audit")) {
            Class<?> type = Class.forName(c.getBeanClassName());
            for (var m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)) {
                    deleters.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(deleters)
                .as("감사 로그에 스케줄 삭제가 생겼다 — 보관 정책(Q5)이 확정되기 전에는 "
                        + "지우지 않는다. 조회 창만 1년이다")
                .isEmpty();
    }
}
