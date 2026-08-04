package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 로그인 계정 (학생/학부모/직원 공통).
 *
 * <p>{@code student}/{@code guardian}/{@code employee}/{@code teacher} 넷 중 정확히 하나만 채워진다
 * (DB에 {@code num_nonnulls(...) = 1} 체크 제약이 걸려 있다).
 *
 * <p>학생만 PENDING을 거치고 그동안 앱 접근이 완전히 차단된다. 학부모는 즉시 ACTIVE다.
 *
 * <p><b>학생 가입 승인은 행정(Employee)이 한다</b> — 담당선생님(Teacher)이 아니다.
 *
 * <p>학생 재가입 중복 차단은 {@code loginId} UNIQUE만으로 부족하다 —
 * <b>PENDING 상태의 기존 승인요청까지 함께 검사</b>해야 한다(승인 전이라도 이미 신청한 사람이다).
 */
@Getter
@Entity
@Table(name = "account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    private AccountType accountType;

    /** 전화번호(학생·학부모) 또는 사번(직원). */
    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guardian_id")
    private ParentGuardian guardian;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    private Account(AccountType accountType, String loginId, String passwordHash, AccountStatus status) {
        this.accountType = accountType;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.status = status;
    }

    /** 학생 계정은 관리자 승인 전까지 PENDING이다. */
    public static Account forStudent(Student student, String loginId, String passwordHash) {
        Account account = new Account(AccountType.STUDENT, loginId, passwordHash, AccountStatus.PENDING);
        account.student = student;
        return account;
    }

    /** 학부모는 승인 절차 없이 즉시 ACTIVE다. */
    public static Account forGuardian(ParentGuardian guardian, String loginId, String passwordHash) {
        Account account = new Account(AccountType.PARENT, loginId, passwordHash, AccountStatus.ACTIVE);
        account.guardian = guardian;
        return account;
    }

    public static Account forEmployee(Employee employee, String loginId, String passwordHash) {
        Account account = new Account(AccountType.EMPLOYEE, loginId, passwordHash, AccountStatus.ACTIVE);
        account.employee = employee;
        return account;
    }

    public static Account forTeacher(Teacher teacher, String loginId, String passwordHash) {
        Account account = new Account(AccountType.TEACHER, loginId, passwordHash, AccountStatus.ACTIVE);
        account.teacher = teacher;
        return account;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    /** 학생 회원가입 승인 (PENDING → ACTIVE). 승인 주체는 행정(Employee)이다. */
    public void approve() {
        this.status = AccountStatus.ACTIVE;
    }

    /** 휴원 등 일시 정지. 복귀 시 {@link #reactivate()}로 되돌린다. */
    public void suspend() {
        if (this.status == AccountStatus.PENDING) {
            return;
        }
        this.status = AccountStatus.SUSPENDED;
    }

    /** 퇴원·제적·수료. 되돌리려면 다시 승인 절차를 탄다. */
    public void withdraw() {
        this.status = AccountStatus.WITHDRAWN;
    }

    /**
     * 재원 복귀.
     *
     * <p><b>{@code PENDING}은 건드리지 않는다.</b> 가입 승인 전인 계정이 여기서 활성화되면
     * 학생 승인제(승인 전 앱 접근 완전 차단, CLAUDE.md §3)가 그대로 뚫린다.
     * 재원 상태와 가입 승인은 별개 축이다.
     */
    public void reactivate() {
        if (this.status == AccountStatus.PENDING) {
            return;
        }
        this.status = AccountStatus.ACTIVE;
    }
}
