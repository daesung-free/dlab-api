package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 월별 교습일수. <b>1일 교습비의 분모</b>다.
 *
 * <h2>★ 달력 일수가 아니다</h2>
 * 클라이언트가 준 표가 <b>2월 27일 · 9월 29일</b>로 잡혀 있다(달력은 28·30).
 * 설·추석 당일을 뺀 것으로 보이지만 확정되지 않았고, 삼일절·어린이날·광복절 등은
 * 빼지 않았다(3·5·8·10·12월이 전부 31일).
 *
 * <h2>★ 그래서 {@code holiday} 테이블로 계산하지 않는다</h2>
 * 그건 <b>급식 가능일</b>용이라 법정공휴일이 전부 들어간다 — 걸면 3월이 30일이 돼서
 * 지금 값보다 오히려 더 틀린다. <b>"급식 쉬는 날"과 "교습비에서 빼는 날"은 다른 개념</b>이다.
 * 규칙을 추측해 자동 산출하면 조용히 틀린 금액이 나온다.
 *
 * <p>대신 학원이 아는 값을 그대로 받는다. <b>연 1회 12행</b>이면 되고, 그것만으로
 * 손계산 수백 칸(할인 6단계 × 상품 5종 × 12개월)이 사라진다.
 *
 * <p>이 값이 <b>세 군데</b>에 쓰인다 — 중도 입학 결제(교습일수 × 1일 교습비),
 * 퇴원 시 독서실비 일할 환불, 장학 취소 시 정상가 재결제. 틀리면 셋 다 틀린다.
 */
@Getter
@Entity
@Table(name = "tuition_month")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TuitionMonth extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. 지점별 휴원일이 다르면 그 지점만 따로 넣는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(name = "month", nullable = false)
    private short month;

    @Column(name = "teaching_days", nullable = false)
    private short teachingDays;

    public TuitionMonth(Academy academy, short year, int month, int teachingDays) {
        this.academy = academy;
        this.year = year;
        this.month = (short) month;
        this.teachingDays = (short) teachingDays;
    }

    public static TuitionMonth common(short year, int month, int teachingDays) {
        return new TuitionMonth(null, year, month, teachingDays);
    }

    public void changeTeachingDays(int teachingDays) {
        this.teachingDays = (short) teachingDays;
    }

    public boolean isCommon() {
        return academy == null;
    }
}
