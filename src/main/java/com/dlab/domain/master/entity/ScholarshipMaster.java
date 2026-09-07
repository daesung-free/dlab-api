package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장학 종류 마스터.
 *
 * <h2>★ 세 곳을 잇는 키가 {@link #code}다</h2>
 * <ul>
 *   <li>{@code scholarship.scholarship_type} — 학생에게 부여된 장학</li>
 *   <li>{@code scholarship_cancel_rule.scholarship_type} — 그 장학의 취소 기준</li>
 *   <li>여기 — 이름과 할인율의 정의</li>
 * </ul>
 * 앞의 둘이 자유 문자열이라, 데스크가 {@code KICE-50}이라고 치면 규칙의 {@code KICE_50}과
 * 안 맞아 <b>그 학생만 취소 판정에서 조용히 빠진다</b>. 오류가 나지 않고 검토 목록에
 * 안 뜰 뿐이라 아무도 알아채지 못한다. 그래서 부여 시 이 마스터를 거치게 한다.
 *
 * <h2>★ 할인율을 여기서 정한다</h2>
 * 같은 장학인데 학생마다 다른 할인율이 들어가면 퇴원 소급 재결제(0820 규정)가
 * <b>"이 학생은 왜 40%였나"</b>에 답할 수 없다. 예외 할인이 필요하면 마스터 행을 추가한다.
 *
 * <p>{@code academy}가 {@code null}이면 전 지점 공통이다 — {@code tuition_price}와 같은 규약.
 */
@Getter
@Entity
@Table(name = "scholarship_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    /** <b>취소 규칙과 정확히 같아야 한다.</b> */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 부여 시 {@code Scholarship}으로 복사된다. 학생별로 다르게 넣지 않는다. */
    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "memo", length = 200)
    private String memo;

    public ScholarshipMaster(Academy academy, short year, String code, String name,
                             BigDecimal discountRate, short sortOrder, String memo) {
        this.academy = academy;
        this.year = year;
        this.code = code;
        this.name = name;
        this.discountRate = discountRate;
        this.sortOrder = sortOrder;
        this.memo = memo;
    }

    /**
     * 이름·할인율 수정.
     *
     * <p><b>{@code code}는 바꾸지 않는다</b> — 이미 부여된 장학과 취소 규칙이 그 값으로
     * 이어져 있어, 바꾸면 그 학생들이 규칙에서 통째로 빠진다. 바꿀 일이면 새 행을 만든다.
     *
     * <p>할인율 변경은 <b>앞으로 부여될 건에만</b> 적용된다. 이미 부여된 건은
     * 부여 시점 값을 들고 있다 — 소급해서 바뀌면 과거 청구의 근거가 흔들린다.
     */
    public void update(String name, BigDecimal discountRate, short sortOrder, String memo) {
        this.name = name;
        this.discountRate = discountRate;
        this.sortOrder = sortOrder;
        this.memo = memo;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public boolean isCommon() {
        return academy == null;
    }
}
