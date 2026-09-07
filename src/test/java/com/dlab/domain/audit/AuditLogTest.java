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
     * 유니크 제약에 걸린다. 실행 간에도 남으므로 시각 기반으로 만든다.
     */
    private static String uniqueCode() {
        return "A" + (System.nanoTime() % 100_000);
    }

    @BeforeEach
    void setUp() {
        String code = uniqueCode();
        academy = tx.execute(status ->
                academyRepository.save(new Academy(code, "감사테스트", LocalTime.of(9, 0))));

        Long accountId = tx.execute(status -> {
            Employee employee = employeeRepository.save(new Employee(academy, "감사행정"));
            return accountRepository.save(
                    Account.forEmployee(employee, "audit" + code, "x")).getId();
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
}
