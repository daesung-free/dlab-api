package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학부모. 자녀 수만큼 계정을 나누지 않고, 계정 1개에 자녀 여러 명을 연결한다(CLAUDE.md §3).
 */
@Getter
@Entity
@Table(name = "parent")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Parent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true)
    private UserAccount userAccount;

    public Parent(UserAccount userAccount) {
        this.userAccount = userAccount;
    }
}
