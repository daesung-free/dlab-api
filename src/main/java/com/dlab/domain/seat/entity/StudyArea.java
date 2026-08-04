package com.dlab.domain.seat.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자습 구역(독서실). <b>DSA {@code area_cd} 체계를 그대로 쓴다</b> —
 * 키오스크가 이 코드로 좌석을 조회하므로 우리 내부 id로 바꾸면 안 된다.
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

    public StudyArea(Academy academy, String areaCd, String areaNm, short sortOrder) {
        this.academy = academy;
        this.areaCd = areaCd;
        this.areaNm = areaNm;
        this.sortOrder = sortOrder;
        this.active = true;
    }
}
