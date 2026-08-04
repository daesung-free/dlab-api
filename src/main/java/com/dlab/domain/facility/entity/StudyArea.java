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
}
