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
}
