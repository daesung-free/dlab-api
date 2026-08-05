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

    /**
     * 임시 비밀번호 상태. {@code true}면 비밀번호 변경 외 모든 API가 차단된다
     * (앱 요구사항 A-1 "임시 비밀번호 최초 로그인 시 변경 강제").
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * 로그인 실패 누적 잠금 시각. {@code null}이면 잠기지 않음.
     *
     * <p><b>{@code status}와 분리한다</b> — SUSPENDED(관리자 정지)와 섞으면
     * 잠금 해제만으로 정지된 계정이 되살아난다.
     */
    @Column(name = "locked_at")
    private Instant lockedAt;

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

    /**
     * 퇴원·제적·수료 시 앱 접근 차단.
     *
     * <p><b>계정을 지우지 않는다</b> — 재등록 시 같은 사람을 다시 찾아야 하고,
     * 지난 로그인 이력도 감사 대상이다.
     */
    public void deactivate() {
        this.status = AccountStatus.WITHDRAWN;
    }

    // ─────────────────────────────────────────────────────────────
    // 로그인 보안 (A-1 · F-4.12-1)
    // ─────────────────────────────────────────────────────────────

    public boolean isLocked() {
        return lockedAt != null;
    }

    /** 로그인 실패 누적 잠금. 자동 해제는 없고 관리자가 푼다(F-4.12-1). */
    public void lock(Instant at) {
        this.lockedAt = at;
    }

    /** 관리자 잠금 해제. 실패 카운터 초기화는 호출자가 함께 해야 한다. */
    public void unlock() {
        this.lockedAt = null;
    }

    /**
     * 관리자의 임시 비밀번호 재발급 (분실 시에 한함 — F-4.12-1).
     *
     * <p>잠금도 함께 푼다. 비밀번호를 잊어 여러 번 틀리다 잠긴 경우가 대부분이라,
     * 재발급만 하고 잠금을 남기면 새 비밀번호로도 로그인이 안 돼 문의가 두 번 온다.
     */
    public void issueTemporaryPassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
        this.mustChangePassword = true;
        this.lockedAt = null;
    }

    /**
     * 본인 비밀번호 변경. 변경과 동시에 강제 플래그가 풀린다.
     *
     * <p>{@code passwordChangedAt}을 남기는 이유는 만료 정책(NF-05 미확보)이 나중에
     * 확정될 때 <b>그 시점 이후 데이터만으로는 판정할 수 없기</b> 때문이다.
     */
    public void changePassword(String encodedPassword, Instant at) {
        this.passwordHash = encodedPassword;
        this.mustChangePassword = false;
        this.passwordChangedAt = at;
    }
}
