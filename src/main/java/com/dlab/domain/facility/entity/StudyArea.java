package com.dlab.domain.facility.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자습 구역. 키오스크가 좌석 상태를 조회할 때 구역 단위로 묶는다.
 *
 * <p>스키마는 V2(다른 담당자)에서 왔다. 여기서는 <b>배정(쓰기)</b>만 다루고,
 * 키오스크 조회는 그쪽이 담당한다.
 */
@Getter
@Entity
@Table(name = "study_area")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyArea extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "area_cd", nullable = false, length = 50)
    private String areaCd;

    @Column(name = "area_nm", nullable = false, length = 100)
    private String areaNm;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * 구역 생성.
     *
     * <p>{@code areaCd}는 <b>DSA 체계를 그대로 쓴다</b> — 키오스크가 이 코드로 좌석을
     * 조회하므로 우리 내부 id로 바꾸면 안 된다(docs/dsa-compat.md 3.7·3.8).
     */
    public StudyArea(Academy academy, String areaCd, String areaNm, short sortOrder) {
        this.academy = academy;
        this.areaCd = areaCd;
        this.areaNm = areaNm;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    /**
     * 구역 정보 수정.
     *
     * <p><b>{@code areaCd}는 바꾸지 않는다.</b> 키오스크가 이 코드로 좌석을 조회하므로
     * (3.7·3.8) 바꾸면 단말이 그 구역을 못 찾는다. 이름·정렬만 고친다.
     */
    public void update(String areaNm, Short sortOrder) {
        if (areaNm != null) {
            this.areaNm = areaNm;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /**
     * 노출 여부.
     *
     * <p>비활성이면 관리자 화면 구역 목록에서 빠진다 — 삭제가 아니라 "쓰지 않는 구역"이다.
     */
    public void changeActive(boolean active) {
        this.active = active;
    }

    /** 되살릴 때 코드가 아니라 이름·정렬만 새 값으로 덮는다. */
    public void reviveAs(String areaNm, short sortOrder) {
        restore();
        this.areaNm = areaNm;
        this.sortOrder = sortOrder;
        this.active = true;
    }
}
