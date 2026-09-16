package com.dlab.domain.menu.entity;

import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계정에 허용된 메뉴 한 칸.
 *
 * <h2>★ 행이 하나도 없으면 "제한 없음"이다</h2>
 * "아무것도 못 봄"이 아니다 — 그렇게 두면 <b>설정을 만들지 않은 기존 계정이 전부 잠긴다.</b>
 * 제한은 명시적으로 지정한 계정에만 걸린다.
 */
@Getter
@Entity
@Table(name = "account_menu")
@IdClass(AccountMenu.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountMenu {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AccountMenu(Account account, Menu menu) {
        this.account = account;
        this.menu = menu;
    }

    public record Key(Long account, Long menu) implements java.io.Serializable {
        public Key() {
            this(null, null);
        }
    }
}
