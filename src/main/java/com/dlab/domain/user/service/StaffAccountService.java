package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.audit.AuditEntityListener;
import com.dlab.domain.audit.AuditRecorder;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 직원·선생님 계정과 권한 관리 (요구사항 F-4.10-2).
 *
 * <p><b>선생님({@link Teacher})과 직원({@link Employee})은 다른 테이블이다.</b>
 * 담당선생님(사감)은 {@code teacher}, 행정선생님을 포함한 행정 조직은 {@code employee}다.
 * 반 담임·승인 에스컬레이션은 {@code teacher}만 가능하고, 그 자격은 FK가 보장한다.
 *
 * <p>역할은 {@code account} 기준으로 부여한다 — 선생님·직원 어느 쪽인지 매번 분기하지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class StaffAccountService {

    /** 감사 로그의 대상 이름. 화면이 이 값으로 계정 이력을 걸러 본다. */
    private static final String AUDIT_ACCOUNT = "Account";

    private final TeacherRepository teacherRepository;
    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final AcademyRepository academyRepository;
    private final PasswordEncoder passwordEncoder;
    /**
     * 권한·상태 변경을 감사 로그로 남긴다 — 안 남기면 그 기간은 나중에 복구할 수 없다.
     *
     * <p><b>{@code @Audited} 리스너로는 안 잡힌다</b> — 역할은 {@code account_role}
     * 조인 테이블에 있어 엔티티 라이프사이클을 타지 않고, 계정 상태 변경도 "무엇에서
     * 무엇으로"를 남겨야 화면이 쓸 수 있다. 그래서 여기서 직접 부른다.
     */
    private final AuditRecorder auditRecorder;

    @Transactional(readOnly = true)
    public List<Teacher> teachers(Long academyId, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return teacherRepository.findByAcademyId(academyId);
    }

    @Transactional(readOnly = true)
    public List<Employee> employees(Long academyId, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return employeeRepository.findByAcademyId(academyId);
    }

    /**
     * 계정 목록 (F-4.10-2 사용자 관리).
     *
     * <p><b>사람 목록({@link #teachers}·{@link #employees})과 다르다.</b> 저쪽은 담임 지정·
     * 승인자 선택용이라 이름·연락처만 주는데, 사용자 관리 화면은 <b>로그인 아이디·계정
     * 상태·권한·잠금 여부</b>를 보여주는 곳이다.
     *
     * <p>역할은 한 번에 조회한다 — 계정마다 부르면 목록 크기만큼 쿼리가 나간다.
     */
    @Transactional(readOnly = true)
    public List<AccountRow> accounts(Long academyId, AccountStatus status,
                                     AuthPrincipal principal) {
        Long scope = academyId;
        if (!principal.allAcademy()) {
            // 전 지점 권한이 없으면 요청 값과 무관하게 자기 지점으로 고정한다
            scope = principal.academyScopeFilter();
        } else if (academyId != null) {
            verifyAccess(academyId, principal);
        }

        List<Account> accounts = accountRepository.findStaffAccounts(scope, status);
        if (accounts.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<String>> rolesByAccount = accountRoleRepository
                .findRoleNamesByAccountIds(accounts.stream().map(Account::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        r -> ((Number) r[0]).longValue(),
                        Collectors.mapping(r -> (String) r[1], Collectors.toSet())));

        return accounts.stream()
                .map(a -> AccountRow.of(a, rolesByAccount.getOrDefault(a.getId(), Set.of())))
                .toList();
    }

    /**
     * 계정 한 줄.
     *
     * @param academyId  소속에서 나온다. 계정 자체에는 지점이 없다
     * @param locked     로그인 실패 5회로 잠긴 상태. <b>자동 해제가 없다</b> — 관리자가 푼다
     * @param mustChangePassword 임시 비밀번호 상태. 바꾸기 전에는 다른 API가 전부 막힌다
     */
    public record AccountRow(Long accountId, String loginId, AccountType accountType,
                             AccountStatus status, Set<String> roles,
                             Long personId, String name, String phone,
                             String deptName, String positionName,
                             Long academyId, String academyName,
                             boolean locked, boolean mustChangePassword,
                             java.time.Instant lastLoginAt) {

        static AccountRow of(Account a, Set<String> roles) {
            Teacher t = a.getTeacher();
            Employee e = a.getEmployee();

            Long personId = t != null ? t.getId() : e != null ? e.getId() : null;
            String name = t != null ? t.getName() : e != null ? e.getName() : null;
            // 연락처는 사람 쪽에 있다 — 계정에는 없다. 선생님·직원 어느 쪽이든 꺼낸다
            String phone = t != null ? t.getPhone() : e != null ? e.getPhone() : null;
            var academy = t != null ? t.getAcademy() : e != null ? e.getAcademy() : null;

            return new AccountRow(a.getId(), a.getLoginId(), a.getAccountType(), a.getStatus(),
                    roles, personId, name, phone,
                    e == null ? null : e.getDeptName(),
                    e == null ? null : e.getPositionName(),
                    academy == null ? null : academy.getId(),
                    academy == null ? null : academy.getName(),
                    a.getLockedAt() != null, a.isMustChangePassword(), a.getLastLoginAt());
        }
    }

    /**
     * 선생님 등록 + 로그인 계정 생성.
     *
     * <p>계정과 사람을 따로 만들 수 있게 하면 "계정 없는 선생님"이 생겨 담임 지정은 되는데
     * 로그인은 안 되는 상태가 된다. 한 번에 만든다.
     */
    @Transactional
    public Teacher createTeacher(Long academyId, String name, String phone, String email,
                                 String loginId, String rawPassword,
                                 Set<Role> roles, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        verifyLoginIdAvailable(loginId);

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Teacher teacher = teacherRepository.save(new Teacher(academy, name, phone));
        teacher.updateContact(phone, email);

        Account account = accountRepository.save(Account.forTeacher(
                teacher, loginId, passwordEncoder.encode(rawPassword), needsApproval(principal)));
        grantRoles(account.getId(), roles);
        return teacher;
    }

    @Transactional
    public Employee createEmployee(Long academyId, String name, String deptName, String positionName,
                                   String phone, String email, String loginId, String rawPassword,
                                   Set<Role> roles, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        verifyLoginIdAvailable(loginId);

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        Employee employee = employeeRepository.save(new Employee(academy, name));
        employee.updateProfile(deptName, positionName, phone, email);

        Account account = accountRepository.save(Account.forEmployee(
                employee, loginId, passwordEncoder.encode(rawPassword), needsApproval(principal)));
        grantRoles(account.getId(), roles);
        return employee;
    }

    /**
     * 승인이 필요한 계정인가 — <b>지점이 만들면 승인 대기, 본사가 만들면 즉시 사용.</b>
     *
     * <p>요구사항정의서 3시트의 "승인대기 → 승인 / 탈퇴"를 발주 확인(2026-09-08)으로
     * 켠 것이다.
     *
     * <p><b>본사가 만든 계정까지 승인 대기로 두지 않는다.</b> 만드는 사람과 승인하는
     * 사람이 같으면 절차만 하나 늘 뿐 아무것도 막지 못한다. 승인이 실제로 의미를 갖는
     * 경우는 <b>지점이 만든 계정을 본사가 확인</b>할 때다({@link #approve}도 본사 전용).
     *
     * <p>⚠️ 켜면서 <b>"등록하면 그 계정으로 바로 로그인된다"가 지점 경로에서는 깨진다.</b>
     * 계정과 사람을 한 번에 만드는 이유가 "담임 지정은 되는데 로그인은 안 되는 선생님"을
     * 막는 것이었는데, 지점이 만든 계정은 승인 전까지 정확히 그 상태다 — 의도된 것이고
     * 화면이 <b>승인 대기임을 알려야 한다.</b>
     */
    private boolean needsApproval(AuthPrincipal principal) {
        return !principal.allAcademy();
    }

    /**
     * 계정 승인 (PENDING → ACTIVE).
     *
     * <p><b>본사만 할 수 있다</b> — 지점이 자기가 만든 계정을 스스로 승인하면 절차가
     * 아무것도 막지 못한다.
     */
    @Transactional
    public Account approve(Long accountId, AuthPrincipal principal) {
        if (!principal.allAcademy()) {
            throw new BusinessException(ErrorCode.ACCOUNT_APPROVAL_FORBIDDEN);
        }
        Account account = loadStaffAccount(accountId);
        if (account.getStatus() != AccountStatus.PENDING) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_PENDING);
        }
        account.approve();
        auditRecorder.recordChanges(AUDIT_ACCOUNT, accountId, academyIdOf(account),
                AuditEntityListener.Change.of("status",
                        AccountStatus.PENDING.name(), AccountStatus.ACTIVE.name()));
        return account;
    }

    /**
     * 계정 탈퇴 (→ WITHDRAWN).
     *
     * <p><b>지우지 않는다</b> — 지난 로그인 이력과 이 계정이 남긴 작업 기록이 감사 대상이다.
     * 되살릴 일이 있으면 승인으로 다시 올린다.
     */
    @Transactional
    public Account withdraw(Long accountId, AuthPrincipal principal) {
        Account account = loadStaffAccount(accountId);
        verifyAccountScope(account, principal);
        AccountStatus before = account.getStatus();
        account.deactivate();
        auditRecorder.recordChanges(AUDIT_ACCOUNT, accountId, academyIdOf(account),
                AuditEntityListener.Change.of("status",
                        before.name(), account.getStatus().name()));
        return account;
    }

    /** 학생·학부모 계정은 이 화면 대상이 아니다 — 가입·승인 흐름이 통째로 다르다. */
    private Account loadStaffAccount(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getAccountType() != AccountType.EMPLOYEE
                && account.getAccountType() != AccountType.TEACHER) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    private void verifyAccountScope(Account account, AuthPrincipal principal) {
        Teacher t = account.getTeacher();
        Employee e = account.getEmployee();
        Academy academy = t != null ? t.getAcademy() : e != null ? e.getAcademy() : null;
        if (academy != null) {
            verifyAccess(academy.getId(), principal);
        }
    }

    /**
     * 역할 재설정. 기존 역할을 지우고 새로 부여한다.
     *
     * <p>이미 발급된 Access Token에는 옛 역할이 남아 있다 — 만료(기본 1시간)되거나 재발급받아야
     * 반영된다. 즉시 차단이 필요하면 로그아웃(블랙리스트)까지 해야 한다.
     */
    @Transactional
    public void replaceRoles(Long accountId, Set<Role> roles, AuthPrincipal principal) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        Long targetAcademyId = academyIdOf(account);
        if (targetAcademyId != null) {
            verifyAccess(targetAcademyId, principal);
        }

        // SUPER_ADMIN 부여는 상위 관리자만 — 지점 관리자가 스스로를 승격할 수 없어야 한다
        if (roles.contains(Role.SUPER_ADMIN) && !principal.hasRole(Role.SUPER_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "상위 관리자 권한은 부여할 수 없습니다.");
        }

        // ★ 지우기 전에 읽는다 — 지운 뒤엔 "무엇이었는지"를 알 방법이 없다.
        //   이게 없으면 "누가 이 계정에서 SUPER_ADMIN을 뺐나"에 답할 수 없다.
        Set<String> before = accountRoleRepository.findRoleNamesByAccountId(accountId);

        accountRoleRepository.deleteByAccountId(accountId);
        grantRoles(accountId, roles);

        auditRecorder.recordChanges(AUDIT_ACCOUNT, accountId, targetAcademyId,
                AuditEntityListener.Change.of("roles", joinRoles(before),
                        joinRoles(roles.stream().map(Role::name).collect(Collectors.toSet()))));
    }

    /** 화면에 그대로 찍히는 값이라 순서를 고정한다 — 안 그러면 같은 역할 집합이 매번 달라 보인다. */
    private String joinRoles(Set<String> roles) {
        return roles.stream().sorted().collect(Collectors.joining(", "));
    }

    /**
     * 직원 목록에 붙일 계정·역할을 <b>한 번에</b> 받는다.
     *
     * <p>행마다 조회하면 쿼리가 인원수만큼 나간다. 그리고 <b>계정이 없는 직원이 정상</b>이다 —
     * 등록만 하고 계정은 나중에 주는 경우가 있어, 없는 것을 오류로 보면 목록이 안 뜬다.
     */
    @Transactional(readOnly = true)
    public StaffAccounts accountsOfTeachers(java.util.List<Long> teacherIds) {
        if (teacherIds.isEmpty()) {
            return StaffAccounts.EMPTY;
        }
        return bind(accountRepository.findByTeacherIds(teacherIds),
                a -> a.getTeacher().getId());
    }

    @Transactional(readOnly = true)
    public StaffAccounts accountsOfEmployees(java.util.List<Long> employeeIds) {
        if (employeeIds.isEmpty()) {
            return StaffAccounts.EMPTY;
        }
        return bind(accountRepository.findByEmployeeIds(employeeIds),
                a -> a.getEmployee().getId());
    }

    private StaffAccounts bind(java.util.List<com.dlab.domain.user.entity.Account> accounts,
                               java.util.function.Function<
                                       com.dlab.domain.user.entity.Account, Long> ownerId) {
        java.util.Map<Long, com.dlab.domain.user.entity.Account> byOwner = new java.util.HashMap<>();
        accounts.forEach(a -> byOwner.put(ownerId.apply(a), a));

        java.util.Map<Long, Set<String>> rolesByAccount = new java.util.HashMap<>();
        if (!accounts.isEmpty()) {
            accountRoleRepository.findRoleNamesByAccountIds(
                    accounts.stream().map(com.dlab.domain.user.entity.Account::getId).toList())
                    .forEach(row -> rolesByAccount
                            .computeIfAbsent(((Number) row[0]).longValue(),
                                    k -> new java.util.LinkedHashSet<>())
                            .add((String) row[1]));
        }
        return new StaffAccounts(byOwner, rolesByAccount);
    }

    /** @param byOwner 선생님·직원 id → 계정. <b>없을 수 있다</b>(계정 미발급) */
    public record StaffAccounts(java.util.Map<Long, com.dlab.domain.user.entity.Account> byOwner,
                                java.util.Map<Long, Set<String>> rolesByAccount) {

        static final StaffAccounts EMPTY = new StaffAccounts(java.util.Map.of(), java.util.Map.of());

        public com.dlab.domain.user.entity.Account accountOf(Long ownerId) {
            return byOwner.get(ownerId);
        }

        public Set<String> rolesOf(Long ownerId) {
            var account = byOwner.get(ownerId);
            return account == null ? Set.of()
                    : rolesByAccount.getOrDefault(account.getId(), Set.of());
        }
    }

    @Transactional(readOnly = true)
    public Set<String> rolesOf(Long accountId) {
        return accountRoleRepository.findRoleNamesByAccountId(accountId);
    }

    private void grantRoles(Long accountId, Set<Role> roles) {
        for (Role role : roles) {
            accountRoleRepository.grant(accountId, role.name());
        }
    }

    private void verifyLoginIdAvailable(String loginId) {
        if (accountRepository.findByLoginId(loginId).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 사용 중인 로그인 아이디입니다.");
        }
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    private Long academyIdOf(Account account) {
        if (account.getTeacher() != null) {
            return account.getTeacher().getAcademy().getId();
        }
        if (account.getEmployee() != null) {
            return account.getEmployee().getAcademy().getId();
        }
        return null;
    }
}
