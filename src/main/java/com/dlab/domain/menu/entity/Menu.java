package com.dlab.domain.menu.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 웹 메뉴 카탈로그.
 *
 * <h2>왜 데이터인가</h2>
 * 메뉴는 화면이 늘 때마다 늘어난다. 코드에 박으면 메뉴 하나 추가에 배포가 필요하고,
 * <b>프론트가 쓰는 식별자와 어긋나도 알 방법이 없다.</b>
 *
 * <h2>★ path_prefix 가 서버 차단의 기준이다</h2>
 * 화면에서 감추는 것만으로는 주소를 직접 친 요청을 막지 못한다. 비워 두면 그 메뉴는
 * <b>화면에서만 감춰지고 서버는 막지 않는다</b> — 목록 API 가 그 사실을 드러낸다.
 */
@Getter
@Entity
@Table(name = "menu")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ★ 프론트 메뉴 식별자와 같은 값이어야 한다 */
    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "parent_code", length = 50)
    private String parentCode;

    @Column(name = "path_prefix", length = 200)
    private String pathPrefix;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /**
     * 이 요청이 이 메뉴에 속하는가. 접두사가 없으면 서버는 판단하지 않는다.
     *
     * <p>★ <b>경계를 본다.</b> 단순 {@code startsWith} 면 {@code /staff} 가
     * {@code /staff-cards} 까지 삼킨다 — 직원 계정 메뉴를 줬을 뿐인데 직원증 화면이 같이
     * 열리고, 반대로 안 줬으면 같이 막힌다.
     */
    public boolean covers(String requestUri) {
        if (pathPrefix == null || pathPrefix.isBlank() || !requestUri.startsWith(pathPrefix)) {
            return false;
        }
        if (requestUri.length() == pathPrefix.length()) {
            return true;
        }
        return requestUri.charAt(pathPrefix.length()) == '/';
    }

    /** 서버가 실제로 막을 수 있는 메뉴인가. 화면이 이걸 보고 「화면 전용」을 표시한다. */
    public boolean enforceable() {
        return pathPrefix != null && !pathPrefix.isBlank();
    }
}
