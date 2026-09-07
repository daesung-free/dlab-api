package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

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

    private final TeacherRepository teacherRepository;
    private final EmployeeRepository employeeRepository;
    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final AcademyRepository academyRepository;
    private final PasswordEncoder passwordEncoder;

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

        Account account = accountRepository.save(
                Account.forTeacher(teacher, loginId, passwordEncoder.encode(rawPassword)));
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

        Account account = accountRepository.save(
                Account.forEmployee(employee, loginId, passwordEncoder.encode(rawPassword)));
        grantRoles(account.getId(), roles);
        return employee;
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

        accountRoleRepository.deleteByAccountId(accountId);
        grantRoles(accountId, roles);
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
