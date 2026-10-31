package com.dlab.domain.menu.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.common.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ★★ 역할별로 <b>기본으로 보이는 메뉴</b> (디랩 1029 8-2).
 *
 * <h2>★ 값이 코드에 있었다</h2>
 * {@code RoleMenuDefaults.BY_MENU} 가 그 표였고, 클래스 주석도
 * <b>「매트릭스가 확정되면 … 값은 데이터로 옮긴다」</b> 였다. 디랩이
 * <b>「권한 매트릭스 화면에서의 수정이 가능할까요?」</b> 라고 물어 옮겼다.
 *
 * <h2>★★ 행이 없는 메뉴는 전 역할에 보인다</h2>
 * ⚠️ 새 메뉴가 <b>조용히 사라지면 기능이 없는 줄 안다.</b> 그 규칙을 그대로 유지한다 —
 * 그래서 <b>「아무에게도 안 보이는 메뉴」를 만들 수는 없다.</b> 그게 필요하면
 * 메뉴 자체를 끄는 쪽이다.
 *
 * <h2>⚠️ 메뉴는 권한이 아니다</h2>
 * 표에서 메뉴를 켜도 <b>서버 {@code @PreAuthorize} 가 막으면 못 쓴다.</b>
 * 화면에 그 설명을 적어 오해를 막아야 한다.
 *
 * <p>★ <b>계정별 지정({@link AccountMenu})이 있으면 그쪽이 이긴다</b> — 우선순위는
 * 그대로다.
 */
@Entity
@Getter
@Table(name = "role_menu_default")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleMenuDefault extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    public RoleMenuDefault(Role role, Menu menu) {
        this.role = role;
        this.menu = menu;
    }
}
