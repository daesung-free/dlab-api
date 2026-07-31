package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 로그인 계정 (학생/학부모/직원 공통).
 * 학생은 관리자 승인 전까지 PENDING이며 이 상태에선 앱 접근이 완전히 차단된다(CLAUDE.md §3).
 */
@Getter
@Entity
@Table(name = "user_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    /** SUPER_ADMIN은 전 지점 접근이므로 null일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public UserAccount(String phone, String passwordHash, String name,
                       UserRole role, AccountStatus status, Branch branch) {
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.status = status;
        this.branch = branch;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** 학생 회원가입 승인 (PENDING → ACTIVE). */
    public void approve() {
        this.status = AccountStatus.ACTIVE;
    }
}
