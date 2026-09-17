package com.dlab.domain.menu.entity;

import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자주 쓰는 메뉴 한 칸 (대시보드 좌측).
 *
 * <h2>★ 노출 설정과 다른 개념이다</h2>
 * {@link AccountMenu} 는 최고관리자가 정하는 <b>권한</b>이고, 이건 본인이 고르는
 * <b>편의</b>다. 한 테이블에 두면 본인이 자기 권한을 넓히는 꼴이 된다.
 */
@Getter
@Entity
@Table(name = "favorite_menu")
@IdClass(FavoriteMenu.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FavoriteMenu {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    /** 사용자가 정한 순서. 저장한 순서대로 보여야 한다 */
    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public FavoriteMenu(Account account, Menu menu, short sortOrder) {
        this.account = account;
        this.menu = menu;
        this.sortOrder = sortOrder;
    }

    public record Key(Long account, Long menu) implements java.io.Serializable {
        public Key() {
            this(null, null);
        }
    }
}
