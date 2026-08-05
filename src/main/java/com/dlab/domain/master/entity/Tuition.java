package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교습비 마스터 (F-4.10-1 · 검수 S-4).
 *
 * <p>전년도 복사 의존순서의 <b>마지막</b>이고 다른 마스터를 참조하지 않는다.
 *
 * <p><b>청구·수납 로직은 여기 없다.</b> 금액 기준만 관리한다 —
 * 실제 청구는 PG 스펙(E-3)과 환불 일할계산 산식(I-26)이 확정돼야 설계할 수 있다.
 */
@Getter
@Entity
@Table(name = "tuition")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tuition extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(nullable = false, length = 100)
    private String name;

    /** 원 단위 정수. <b>금액에 부동소수를 쓰지 않는다</b> — 반올림 오차가 청구액에 남는다. */
    @Column(nullable = false)
    private int amount;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public Tuition(Academy academy, short year, String name, int amount, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.amount = amount;
        this.sortOrder = sortOrder;
    }

    /**
     * {@code null}은 변경하지 않음.
     *
     * <p><b>이 변경은 과거 청구에 소급되면 안 된다.</b> 청구 도메인이 생기면 청구 시점 금액을
     * 청구 행에 복사해 남겨야 한다 — 상벌점이 부여 시점 점수를 복사하는 것과 같은 이유다.
     */
    public void update(String name, Integer amount) {
        if (name != null) {
            this.name = name;
        }
        if (amount != null) {
            this.amount = amount;
        }
    }

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }
}
