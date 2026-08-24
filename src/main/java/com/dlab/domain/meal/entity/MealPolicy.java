package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 정책 — 신청·취소 마감 D-n.
 *
 * <p><b>상수로 박지 않는다.</b> 화면이 드롭다운(1/2/3/5/7)으로 고르게 한다.
 * 신청과 취소가 <b>같은 기준</b>을 쓴다(F-4.5 "앱 신청·취소 모두 이용일 D-n까지").
 */
@Getter
@Entity
@Table(name = "meal_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealPolicy extends BaseEntity {

    /** 미등록 지점의 기본값. 화면 기본 선택도 3일이다. */
    public static final short DEFAULT_DEADLINE_DAYS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "deadline_days", nullable = false)
    private short deadlineDays = DEFAULT_DEADLINE_DAYS;

    public MealPolicy(Academy academy, short year, short deadlineDays) {
        this.academy = academy;
        this.year = year;
        this.deadlineDays = deadlineDays;
    }

    public void changeDeadlineDays(short deadlineDays) {
        this.deadlineDays = deadlineDays;
    }

    /**
     * 이 지점 급식업체. 지점마다 다르다 — 디온푸드 7곳 / 니즈푸드·한샘푸드·한끼애·성림푸드 각 1곳.
     *
     * <p>업체·단가를 별도 테이블로 빼지 않은 이유는, 여기가 이미 (지점 × 연도)로
     * 급식 설정을 들고 있기 때문이다. <b>두 군데로 갈리면 한쪽만 등록된 지점이 생긴다.</b>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private MealVendor vendor;

    /**
     * 한 끼 단가. 점심·저녁이 같다(0820 기준). <b>지점별로 다르다</b> — 대구만 8,000원.
     *
     * <p>비어 있을 수 있다 — 업체 연결 전 지점이다. 그때는 신청 금액을 계산할 수 없다.
     */
    @Column(name = "unit_price")
    private Integer unitPrice;

    public void assignVendor(MealVendor vendor, Integer unitPrice) {
        this.vendor = vendor;
        this.unitPrice = unitPrice;
    }

    /** 금액 계산이 가능한 상태인가. 업체·단가가 없으면 신청은 되지만 청구를 못 만든다. */
    public boolean isPriced() {
        return unitPrice != null && unitPrice > 0;
    }
}
