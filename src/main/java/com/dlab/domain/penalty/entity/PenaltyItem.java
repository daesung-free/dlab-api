package com.dlab.domain.penalty.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상벌점 항목 마스터. 전년도 복사 대상이다. */
@Getter
@Entity
@Table(name = "penalty_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PenaltyItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "point_value", nullable = false)
    private int pointValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PenaltyCategory category;

    public PenaltyItem(Academy academy, short year, String itemName, int pointValue, PenaltyCategory category) {
        this.academy = academy;
        this.year = year;
        this.itemName = itemName;
        this.pointValue = pointValue;
        this.category = category;
    }

    /**
     * 전년도 복사 원본. NULL이면 그 해에 새로 만든 것이다.
     * 복사본과 신규 생성분을 구분할 유일한 근거라 복사 시 반드시 채운다.
     */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }

}
