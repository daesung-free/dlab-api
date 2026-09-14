package com.dlab.domain.facility.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관(본관/별관).
 *
 * <p><b>왜 구역 위에 층을 하나 더 두는가</b> — 동탄2관은 본관과 <b>구역명도 좌석번호도</b>
 * 같다. 구역에 컬럼 하나를 더하는 것으로는 구역 코드 충돌을 풀 수 없어서, 구역의 상위
 * 축으로 뒀다. 별관이 더 생겨도 행만 추가하면 된다(클라이언트 요구사항).
 *
 * <h2>★ {@code seatCdOffset}은 만든 뒤 바꾸지 않는다</h2>
 * 이 값은 좌석을 등록할 때 {@code kiosk_seat_cd}에 이미 반영돼 저장된다. 나중에 바꾸면
 * <b>그 관에 이미 있는 좌석들의 키오스크 번호와 어긋난다</b> — 화면은 멀쩡한데 단말에서만
 * 자리가 밀리는, 원인을 찾기 어려운 상태가 된다. 바꿔야 하면 좌석을 지우고 다시 만든다.
 */
@Getter
@Entity
@Table(name = "building")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Building extends BaseEntity {

    /** 마이그레이션이 지점마다 하나씩 만들어 둔 본관. 기존 구역이 전부 여기 붙어 있다. */
    public static final String MAIN_CODE = "MAIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /** 키오스크에 내릴 좌석번호에 더할 값. 본관은 0. */
    @Column(name = "seat_cd_offset", nullable = false)
    private int seatCdOffset;

    @Column(nullable = false)
    private boolean active = true;

    public Building(Academy academy, String code, String name, short sortOrder, int seatCdOffset) {
        this.academy = academy;
        this.code = code;
        this.name = name;
        this.sortOrder = sortOrder;
        this.seatCdOffset = seatCdOffset;
        this.active = true;
    }

    /** 본관인가 — 키오스크 코드를 변환하지 않는 관이다. */
    public boolean isMain() {
        return seatCdOffset == 0;
    }

    /**
     * 이름·정렬만 고친다.
     *
     * <p><b>{@code code}와 {@code seatCdOffset}은 대상이 아니다.</b> 둘 다 이미 저장된
     * 키오스크 코드에 반영돼 있어, 바꾸면 저장된 값과 어긋난다.
     */
    public void update(String name, Short sortOrder) {
        if (name != null) {
            this.name = name;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
