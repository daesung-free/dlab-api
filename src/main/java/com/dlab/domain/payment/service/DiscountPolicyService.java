package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.DiscountPolicy;
import com.dlab.domain.payment.entity.DiscountType;
import com.dlab.domain.payment.repository.DiscountPolicyRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 할인 정책 관리 (1016 Q3·Q4).
 *
 * <h2>★ 종류 목록이 미회신이어도 돌아간다</h2>
 * 규칙을 데이터로 두었으므로 <b>답이 오면 행만 채운다.</b> 0820 규정의 비율 5종은
 * 시드로 들어가 있고 <b>전부 꺼져 있다</b> — 「이것이 전부인가」가 확인되면 켠다.
 *
 * <h2>⚠️ 독서실비 할인을 만들 수 없게 막는다</h2>
 * 0820 규정상 독서실비는 할인이 없다. 받아 주면 <b>환불 차감이 정가보다 작아져 학원이
 * 손해를 본다</b> — 입력 단계에서 거부하는 것이 나중에 금액을 되짚는 것보다 싸다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscountPolicyService {

    private final DiscountPolicyRepository repository;
    private final AcademyRepository academyRepository;

    /** 그 지점에서 쓸 수 있는 정책. 전 지점 공통도 함께 내린다. 꺼진 것도 포함한다. */
    public List<DiscountPolicy> list(Short year, Long academyId) {
        return repository.findForAcademy(year, academyId);
    }

    @Transactional
    public DiscountPolicy create(Short year, Long academyId, String code, String name,
                                 DiscountType discountType, int discountValue,
                                 BillingItemType targetItemType,
                                 Short applyFromMonth, Short applyToMonth, String note) {
        requireDiscountable(targetItemType);
        requireMonthPair(applyFromMonth, applyToMonth);

        Academy academy = academyId == null ? null : academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        return repository.save(new DiscountPolicy(academy, year, code, name,
                discountType, discountValue, targetItemType,
                applyFromMonth, applyToMonth, note));
    }

    @Transactional
    public void change(Long id, String name, DiscountType discountType, int discountValue,
                       BillingItemType targetItemType,
                       Short applyFromMonth, Short applyToMonth, String note) {
        requireDiscountable(targetItemType);
        requireMonthPair(applyFromMonth, applyToMonth);
        require(id).change(name, discountType, discountValue, targetItemType,
                applyFromMonth, applyToMonth, note);
    }

    /**
     * 켜거나 끈다.
     *
     * <p>★ <b>변경과 경로를 나눈다.</b> 켜는 순간 청구 금액이 달라지므로, 다른 값을 고치다가
     * 함께 켜지면 안 된다.
     */
    @Transactional
    public void changeActive(Long id, boolean active) {
        require(id).changeActive(active);
    }

    @Transactional
    public void delete(Long id) {
        // ★ soft delete 다. 물리 삭제하면 과거 청구가 어느 할인으로 계산됐는지 추적이 끊긴다
        require(id).markDeleted();
    }

    /**
     * 적용할 할인 금액.
     *
     * <p>★ <b>켜진 것 중 그 달에 해당하는 것만</b> 쓴다. 월 범위가 지정된 행이 있으면
     * 그쪽이 연중 기본보다 우선한다 — 1021 의 「1월 30% · 2~3월 20%」가 그 모양이다.
     *
     * <p>⚠️ 둘 다 없으면 <b>0 을 돌려준다.</b> 예외를 던지면 할인을 안 받는 학생의 청구가
     * 막힌다 — 할인이 없는 것이 정상이다.
     */
    public int discountAmount(Short year, Long academyId, String code,
                              BillingItemType itemType, int month, int suppliedAmount) {
        List<DiscountPolicy> candidates = repository.findActiveByCode(year, academyId, code);

        return candidates.stream()
                .filter(p -> p.appliesTo(itemType))
                .filter(p -> p.appliesToMonth(month))
                // ★ 월이 지정된 행을 먼저 — 연중 기본은 그것이 없을 때만 쓴다
                .min((a, b) -> Boolean.compare(a.getApplyFromMonth() == null,
                        b.getApplyFromMonth() == null))
                .map(p -> p.discountAmountOf(suppliedAmount))
                .orElse(0);
    }

    /**
     * ★★ 여러 정책을 <b>합연산</b>으로 적용한다 (디랩 1021 회신).
     *
     * <h2>★ 디랩이 준 규칙</h2>
     * <blockquote>「다중 할인 혜택 적용의 경우 <b>합연산</b>으로 진행됩니다. … 교습비
     * 할인(장학 10% + 친구추천 10% + 경쟁사 이전 10%) 가정했을 때 <b>모든 혜택을 더한
     * 비율(30%)을 교습비에 적용</b>(670,000 − 201,000 = 469,000) 하고
     * 독서실비(120,000)를 더한 <b>589,000원</b>을 수납」</blockquote>
     *
     * <h2>★★ 곱연산이 아니다 — 그래서 적용 순서가 없다</h2>
     * 비율을 차례로 곱하면(0.9 × 0.9 × 0.9 = 0.729) <b>순서에 따라 값이 달라지고</b>
     * 27.1% 가 되어 30% 와 어긋난다. 더하는 데는 순서가 없으므로
     * <b>{@code sort_order}·{@code stackable} 같은 칸이 필요하지 않다.</b>
     *
     * <h2>⚠️ 비율과 금액이 섞이면 순서가 다시 생긴다</h2>
     * 그래서 <b>「비율을 모두 더해 적용한 뒤, 금액 할인을 뺀다」</b>로 고정한다
     * (1021 문서 2-3). 거꾸로 하면 금액 할인분에 비율이 또 걸린다.
     *
     * <h2>⚠️ 합이 100% 를 넘을 수 있다</h2>
     * 상한을 100% 로 두고 <b>음수 청구가 나오지 않게</b> 막는다. 넘은 사실은
     * {@link Applied#overCapped()} 로 드러낸다 — 조용히 자르면 <b>데스크가 할인을
     * 과하게 걸었다는 것을 모른다.</b>
     *
     * @param codes 적용할 정책 코드들. 비거나 {@code null} 이면 할인 0 이다
     */
    @Transactional(readOnly = true)
    public Applied applyAll(Short year, Long academyId, List<String> codes,
                            BillingItemType itemType, int month, int suppliedAmount) {
        if (codes == null || codes.isEmpty() || suppliedAmount <= 0) {
            return new Applied(0, 0, List.of(), false);
        }

        int rateSum = 0;
        int amountSum = 0;
        List<String> detail = new java.util.ArrayList<>();

        for (String code : codes.stream().filter(c -> c != null && !c.isBlank()).distinct().toList()) {
            DiscountPolicy picked = pick(year, academyId, code, itemType, month);
            if (picked == null) {
                continue;
            }
            if (picked.getDiscountType() == DiscountType.RATE) {
                rateSum += picked.getDiscountValue();
                detail.add("%s %d%%".formatted(picked.getName(), picked.getDiscountValue()));
            } else {
                amountSum += picked.getDiscountValue();
                detail.add("%s %,d원".formatted(picked.getName(), picked.getDiscountValue()));
            }
        }

        boolean overCapped = rateSum > 100;
        int cappedRate = Math.min(rateSum, 100);
        // ★ 비율을 먼저 더해 한 번에 적용하고 ★ 그 뒤에 금액을 뺀다
        int byRate = (int) ((long) suppliedAmount * cappedRate / 100);
        int total = Math.min(byRate + amountSum, suppliedAmount);

        if (!detail.isEmpty()) {
            detail.add("= %d%%%s".formatted(cappedRate,
                    amountSum > 0 ? " + %,d원".formatted(amountSum) : ""));
        }
        return new Applied(total, cappedRate, detail, overCapped);
    }

    /**
     * ★ 합연산 결과.
     *
     * @param amount      할인 총액
     * @param rate        적용된 <b>합산 비율</b>(상한 적용 후). 청구가 율로 받으므로 필요하다
     * @param detail      ★ 「장학 10% + 친구추천 10% = 30%」처럼 남길 근거.
     *                    <b>금액만 남기면 어느 혜택이 얼마였는지 재현되지 않는다</b>
     * @param overCapped  ⚠️ 합이 100% 를 넘어 잘렸는가. <b>조용히 자르면 데스크가 모른다</b>
     */
    public record Applied(int amount, int rate, List<String> detail, boolean overCapped) {

        /** 근거 한 줄. {@code Billing.discountSource} 에 박는다. */
        public String source() {
            return detail.isEmpty() ? null : String.join(" + ", detail.subList(0, detail.size() - 1))
                    + " " + detail.get(detail.size() - 1);
        }
    }

    /** 그 코드에 해당하는 정책 하나. ★ 월 지정 행이 연중 기본보다 우선한다. */
    private DiscountPolicy pick(Short year, Long academyId, String code,
                                BillingItemType itemType, int month) {
        return repository.findActiveByCode(year, academyId, code).stream()
                .filter(p -> p.appliesTo(itemType))
                .filter(p -> p.appliesToMonth(month))
                .min((a, b) -> Boolean.compare(a.getApplyFromMonth() == null,
                        b.getApplyFromMonth() == null))
                .orElse(null);
    }

    private DiscountPolicy require(Long id) {
        return repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireDiscountable(BillingItemType itemType) {
        if (itemType != null && !itemType.isDiscountable()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이 항목에는 할인을 붙일 수 없습니다: " + itemType);
        }
    }

    private void requireMonthPair(Short from, Short to) {
        if ((from == null) != (to == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "적용 월은 시작·종료를 함께 지정해야 합니다.");
        }
        if (from != null && from > to) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "적용 시작 월이 종료 월보다 늦습니다.");
        }
    }
}
