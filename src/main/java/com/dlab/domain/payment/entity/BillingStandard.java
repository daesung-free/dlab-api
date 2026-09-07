package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 청구기준 마스터 (F-4.10-5 · 관리자 &gt; 수납관리 &gt; 청구기준 관리).
 *
 * <h2>무엇을 · 얼마에 · 언제 청구하는가</h2>
 * 요구사항이 <i>"교습비·특강비·환불 기준 관리"</i>인데 지금까지 {@link TuitionPrice}
 * (학년 × 좌석유형 단가표) 하나뿐이라 <b>특강비·등록비·급식비가 통째로 빠져</b> 있었다.
 *
 * <h2>★ 금액을 두 방식으로 갖는다</h2>
 * <ul>
 *   <li>{@link AmountSource#FIXED} — 특강비·등록비처럼 값 하나로 끝나는 것</li>
 *   <li>{@link AmountSource#PRICE_MATRIX} — 교습비. 학년 × 좌석유형으로 갈려
 *       (N수 750,000 / 재학생 490,000 / 1인실 지점별) <b>한 칸에 넣을 수 없다</b>.
 *       금액을 갖지 않고 {@code tuition_price}를 가리킨다</li>
 * </ul>
 * 교습비에 대표값 하나를 박아두면 <b>화면 금액과 실제 청구액이 갈린다</b> —
 * 어느 쪽이 맞는지 데스크가 알 방법이 없다.
 *
 * <h2>지우지 않고 내린다</h2>
 * 지난 기수 기준을 삭제하면 <b>그 기수 청구가 어느 기준으로 나갔는지</b> 추적이 끊긴다.
 * {@code active = false}로 두면 화면 상태필터에서 빠진다.
 *
 * <p>{@code academy}가 {@code null}이면 전 지점 공통이다 — {@link TuitionPrice}와 같은 규약.
 */
@Getter
@Entity
@Table(name = "billing_standard")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingStandard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    /** 표시용 코드({@code BL-TU-01}). 전표·화면에 나가므로 내부 id를 쓰지 않는다. */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private BillingItemType itemType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 기수(1기·2기…). 교습비는 기수마다 다시 청구된다. */
    @Column(name = "round_name", length = 20)
    private String roundName;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_source", nullable = false, length = 20)
    private AmountSource amountSource;

    /** {@link AmountSource#PRICE_MATRIX}면 {@code null}이다. */
    @Column(name = "amount")
    private Integer amount;

    /** <b>"등록 시"처럼 날짜가 아닌 값이 섞여</b> 자유 문구로 둔다. */
    @Column(name = "due_desc", length = 50)
    private String dueDesc;

    /** 0803에 디랩 자체 PG로 단일화돼 실제 값은 카드·가상계좌뿐이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "memo", length = 200)
    private String memo;

    private BillingStandard(Academy academy, short year, String code, BillingItemType itemType,
                            String name, String roundName, AmountSource amountSource,
                            Integer amount, String dueDesc, PaymentMethod paymentMethod,
                            short sortOrder, String memo) {
        this.academy = academy;
        this.year = year;
        this.code = code;
        this.itemType = itemType;
        this.name = name;
        this.roundName = roundName;
        this.amountSource = amountSource;
        this.amount = amount;
        this.dueDesc = dueDesc;
        this.paymentMethod = paymentMethod;
        this.sortOrder = sortOrder;
        this.memo = memo;
    }

    /** 금액을 직접 갖는 기준 — 특강비·등록비·급식비. */
    public static BillingStandard fixed(Academy academy, short year, String code,
                                        BillingItemType itemType, String name, String roundName,
                                        int amount, String dueDesc, PaymentMethod paymentMethod,
                                        short sortOrder, String memo) {
        return new BillingStandard(academy, year, code, itemType, name, roundName,
                AmountSource.FIXED, amount, dueDesc, paymentMethod, sortOrder, memo);
    }

    /** 교습비 — 금액은 {@code tuition_price} 단가표에서 나온다. */
    public static BillingStandard priceMatrix(Academy academy, short year, String code,
                                              BillingItemType itemType, String name,
                                              String roundName, String dueDesc,
                                              PaymentMethod paymentMethod, short sortOrder,
                                              String memo) {
        return new BillingStandard(academy, year, code, itemType, name, roundName,
                AmountSource.PRICE_MATRIX, null, dueDesc, paymentMethod, sortOrder, memo);
    }

    public void update(String name, String roundName, AmountSource amountSource, Integer amount,
                       String dueDesc, PaymentMethod paymentMethod, short sortOrder, String memo) {
        this.name = name;
        this.roundName = roundName;
        this.amountSource = amountSource;
        // PRICE_MATRIX로 바꾸면 남아 있던 금액을 반드시 지운다 —
        // 남겨두면 DB CHECK에 걸리고, 통과시키면 쓰이지 않는 금액이 화면에 뜬다
        this.amount = amountSource == AmountSource.PRICE_MATRIX ? null : amount;
        this.dueDesc = dueDesc;
        this.paymentMethod = paymentMethod;
        this.sortOrder = sortOrder;
        this.memo = memo;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public boolean isCommon() {
        return academy == null;
    }

    /** 금액이 어디서 오는가. */
    public enum AmountSource {
        /** {@code amount} 컬럼 그대로. */
        FIXED,
        /** {@code tuition_price} 학년 × 좌석유형 단가표. */
        PRICE_MATRIX
    }
}
