package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingStandard;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.repository.BillingStandardRepository;
import com.dlab.domain.payment.repository.TuitionPriceRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 청구기준 관리 (F-4.10-5).
 *
 * <h2>세 화면이 한 줄로 이어진다</h2>
 * <ol>
 *   <li><b>여기(청구기준)</b> — 정가와 청구 시점을 정한다</li>
 *   <li>수납현황 &gt; 할인 정책 — 정가에서 할인을 적용해 실제 청구액을 만든다</li>
 *   <li>결제 — 그 청구액으로 PG 트랜잭션이 생긴다</li>
 * </ol>
 *
 * <h2>★ 교습비 금액은 여기서 정하지 않는다</h2>
 * 학년 × 좌석유형으로 갈려 한 칸에 못 넣는다. 교습비 행은
 * {@link BillingStandard.AmountSource#PRICE_MATRIX}로 두고 화면에는 단가표의
 * <b>금액 범위</b>만 보여준다 — 대표값을 박으면 화면과 실제 청구액이 갈린다.
 *
 * <h2>★ 전 지점 공통은 본사만</h2>
 * {@link TuitionPricingService}와 같은 규약이다. 지점이 공통 행을 고치면 나머지 지점
 * 청구가 같이 바뀐다.
 */
@Service
@RequiredArgsConstructor
public class BillingStandardService {

    private final BillingStandardRepository standardRepository;
    private final TuitionPriceRepository priceRepository;
    private final AcademyRepository academyRepository;

    // ─────────────────────────────────────────── 조회

    /**
     * 청구기준 목록.
     *
     * @param itemType 화면 항목 필터. {@code null}이면 전체
     * @param active   상태 필터. {@code null}이면 전체(사용중 + 중지)
     */
    @Transactional(readOnly = true)
    public List<Row> list(AuthPrincipal me, short year, Long academyId,
                          BillingItemType itemType, Boolean active) {
        requireScope(me, academyId);

        List<BillingStandard> standards =
                standardRepository.findAllByScope(year, academyId, itemType, active);

        // 단가표는 PRICE_MATRIX 행이 하나라도 있을 때만 읽는다
        boolean needsMatrix = standards.stream()
                .anyMatch(s -> s.getAmountSource() == BillingStandard.AmountSource.PRICE_MATRIX);
        List<TuitionPrice> prices = needsMatrix ? applicablePrices(year, academyId) : List.of();

        return standards.stream().map(s -> Row.of(s, prices)).toList();
    }

    /**
     * 그 지점에 적용되는 단가표 전체.
     *
     * <p><b>지점 행이 있으면 그것만, 없으면 공통본</b>을 쓴다 — 학년 × 좌석유형 단위로
     * 가른다. 지점 행이 몇 개만 있는 경우가 실제로 있어서(목동·분당 재학생 교습비만
     * 다르다) 통째로 한쪽을 고르면 나머지 조합이 사라진다.
     */
    private List<TuitionPrice> applicablePrices(short year, Long academyId) {
        Map<String, TuitionPrice> merged = new LinkedHashMap<>();
        for (TuitionPrice p : priceRepository.findAllByScope(year, null)) {
            merged.put(p.getGradeType() + ":" + p.getSeatType(), p);
        }
        if (academyId != null) {
            for (TuitionPrice p : priceRepository.findAllByScope(year, academyId)) {
                merged.put(p.getGradeType() + ":" + p.getSeatType(), p);
            }
        }
        return new ArrayList<>(merged.values());
    }

    // ─────────────────────────────────────────── 관리

    @Transactional
    public BillingStandard create(AuthPrincipal me, Long academyId, short year, String code,
                                  BillingItemType itemType, String name, String roundName,
                                  BillingStandard.AmountSource amountSource, Integer amount,
                                  String dueDesc, PaymentMethod paymentMethod,
                                  short sortOrder, String memo) {
        requireScope(me, academyId);
        String normalized = normalizeCode(code);
        standardRepository.findByCode(year, normalized, academyId).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.BILLING_STANDARD_CODE_DUPLICATED);
        });
        validateAmount(amountSource, amount);

        Academy academy = resolveAcademy(academyId);
        BillingStandard standard =
                amountSource == BillingStandard.AmountSource.PRICE_MATRIX
                        ? BillingStandard.priceMatrix(academy, year, normalized, itemType, name,
                                roundName, dueDesc, paymentMethod, sortOrder, memo)
                        : BillingStandard.fixed(academy, year, normalized, itemType, name,
                                roundName, amount, dueDesc, paymentMethod, sortOrder, memo);

        return standardRepository.save(standard);
    }

    /** 코드·항목·연도·지점은 바꾸지 않는다 — 바꿀 일이면 새 기준을 만드는 게 맞다. */
    @Transactional
    public BillingStandard update(AuthPrincipal me, Long id, String name, String roundName,
                                  BillingStandard.AmountSource amountSource, Integer amount,
                                  String dueDesc, PaymentMethod paymentMethod,
                                  short sortOrder, String memo) {
        BillingStandard standard = load(me, id);
        validateAmount(amountSource, amount);
        standard.update(name, roundName, amountSource, amount, dueDesc, paymentMethod,
                sortOrder, memo);
        return standard;
    }

    /** 사용/중지. 지난 기수 기준은 지우지 않고 내린다. */
    @Transactional
    public BillingStandard changeActive(AuthPrincipal me, Long id, boolean active) {
        BillingStandard standard = load(me, id);
        standard.changeActive(active);
        return standard;
    }

    /**
     * 삭제 — soft delete.
     *
     * <p>물리 삭제하면 <b>그 기준으로 나간 과거 청구의 근거가 사라진다</b>.
     * 대부분의 경우 삭제가 아니라 {@link #changeActive} 중지가 맞다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        load(me, id).markDeleted();
    }

    private BillingStandard load(AuthPrincipal me, Long id) {
        BillingStandard standard = standardRepository.findById(id)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.BILLING_STANDARD_NOT_FOUND));
        requireScope(me, standard.isCommon() ? null : standard.getAcademy().getId());
        return standard;
    }

    private void validateAmount(BillingStandard.AmountSource amountSource, Integer amount) {
        if (amountSource == BillingStandard.AmountSource.FIXED) {
            if (amount == null || amount < 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "금액을 0 이상으로 입력해 주세요.");
            }
        }
    }

    /** 코드는 전표에 나가므로 공백·대소문자를 정리해 넣는다 — 같은 코드가 둘로 갈리는 걸 막는다. */
    private String normalizeCode(String code) {
        String trimmed = code == null ? "" : code.strip().toUpperCase();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "코드를 입력해 주세요.");
        }
        return trimmed;
    }

    private Academy resolveAcademy(Long academyId) {
        return academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /** {@code academyId}가 {@code null}이면 전 지점 공통 — <b>본사만</b> 다룰 수 있다. */
    private void requireScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            if (me.academyScopeFilter() != null) {
                throw new BusinessException(ErrorCode.BILLING_STANDARD_SCOPE_FORBIDDEN);
            }
            return;
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    // ─────────────────────────────────────────── 환불 기준

    /**
     * 환불 기준 — <b>읽기 전용</b>이다.
     *
     * <h2>왜 편집 화면이 아닌가</h2>
     * 목업은 환불 기준을 청구기준과 같은 목록으로 그리지만, 실제 값은
     * <b>학원법 시행령 반환기준</b>이라 학원이 정하는 것이 아니다. 편집을 열면
     * 임의로 바꾼 비율로 환불이 나가고 그게 곧 법 위반이 된다.
     *
     * <p>산식 자체는 {@link RefundCalculator}가 이미 구현하고 있다 — 여기서 내려주는 것은
     * <b>그 구현이 무엇을 하고 있는지의 설명</b>이고, 화면은 그대로 표에 그리면 된다.
     * 두 곳에 값을 두면 화면과 계산이 갈린다.
     */
    public List<RefundRule> refundRules() {
        return List.of(
                new RefundRule(BillingItemType.TUITION, "교습 시작 전", "전액 환불",
                        "학원법 반환기준 첫 행. 차감이 없으므로 할인을 받았어도 추가 징수가 나오지 않는다"),
                new RefundRule(BillingItemType.TUITION, "이용기간 1/3 이내", "2/3 환불",
                        "학원법 반환기준. 차감은 정상가 기준이라 할인을 받았으면 환불액이 음수가 될 수 있다"),
                new RefundRule(BillingItemType.TUITION, "이용기간 1/2 이내", "1/2 환불",
                        "위와 같음"),
                new RefundRule(BillingItemType.TUITION, "이용기간 1/2 초과", "환불 없음",
                        "정가 전액 차감. 할인 받았으면 그 차액을 추가 징수한다"),
                new RefundRule(BillingItemType.STUDY_ROOM, "이용 일수만큼", "일할 차감",
                        "할인이 없다. 전 기간을 다 쓰면 환불 0(절사분을 돌려주지 않는다)"),
                new RefundRule(BillingItemType.MEAL, "앱 — 이용 3일 전까지", "전액 환불",
                        "PG 자동환불. 데스크 취소는 기한 제한이 없다"),
                new RefundRule(BillingItemType.LECTURE, "미확정", "-",
                        "특강 환불 규정 미수령"),
                new RefundRule(BillingItemType.REGISTRATION, "미확정", "-",
                        "등록비 환불 규정 미수령"));
    }

    /**
     * 화면 한 줄.
     *
     * @param amount    {@link BillingStandard.AmountSource#PRICE_MATRIX}면 {@code null}
     * @param amountMin 단가표 최저 월 총액. {@code FIXED}면 {@code null}
     * @param amountMax 단가표 최고 월 총액. {@code FIXED}면 {@code null}
     */
    public record Row(Long id, Long academyId, short year, String code,
                      BillingItemType itemType, String itemLabel, String name, String roundName,
                      BillingStandard.AmountSource amountSource, Integer amount,
                      Integer amountMin, Integer amountMax,
                      String dueDesc, PaymentMethod paymentMethod,
                      boolean active, short sortOrder, String memo) {

        static Row of(BillingStandard s, List<TuitionPrice> prices) {
            Integer min = null;
            Integer max = null;
            if (s.getAmountSource() == BillingStandard.AmountSource.PRICE_MATRIX
                    && !prices.isEmpty()) {
                min = prices.stream().map(TuitionPrice::monthlyTotal)
                        .min(Comparator.naturalOrder()).orElse(null);
                max = prices.stream().map(TuitionPrice::monthlyTotal)
                        .max(Comparator.naturalOrder()).orElse(null);
            }
            return new Row(s.getId(), s.isCommon() ? null : s.getAcademy().getId(), s.getYear(),
                    s.getCode(), s.getItemType(), label(s.getItemType()), s.getName(),
                    s.getRoundName(), s.getAmountSource(), s.getAmount(), min, max,
                    s.getDueDesc(), s.getPaymentMethod(), s.isActive(), s.getSortOrder(),
                    s.getMemo());
        }

        private static String label(BillingItemType type) {
            return switch (type) {
                case TUITION -> "교습비";
                case STUDY_ROOM -> "독서실비";
                case MEAL -> "급식비";
                case LECTURE -> "특강비";
                case REGISTRATION -> "등록비";
                case ETC -> "기타";
            };
        }
    }

    /** 환불 기준 한 줄. 값이 아니라 {@link RefundCalculator} 구현의 설명이다. */
    public record RefundRule(BillingItemType itemType, String period, String rate, String note) {
    }
}
