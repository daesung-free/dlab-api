package com.dlab.domain.payment.service;

import com.dlab.common.excel.ExcelExporter;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.repository.BillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 수납현황·미납자 (F-4.8-1).
 *
 * <h2>청구를 만들었으니 "누가 안 냈나"가 다음이다</h2>
 * 미납자 목록·통계·엑셀. DSA의 <i>"수납현황 &gt; 통합 매출장"</i>을 대체한다.
 *
 * <h2>★ 미납액은 DB가 합산한다</h2>
 * {@code Billing.unpaidAmount()}는 거래를 메모리에서 훑는다. 지점 전체를 그렇게 하면
 * <b>청구 수만큼 거래를 다 끌어온다.</b> 목록 조회는 DB가 합계를 내게 한다.
 *
 * <h2>★ 엑셀은 마스킹이 기본 ON이다</h2>
 * 실행가이드 3.2. 그리고 <b>내려받아 고쳐 다시 올리는 흐름</b>이 실제로 흔해서,
 * 마스킹 판정은 {@code common/privacy/Masking} 하나만 쓴다 — Export와 Import가
 * 같은 판정을 써야 왕복이 성립한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptStatusService {

    /** 기간을 안 주면 쓰는 넓은 범위. {@code LocalDate.MIN}은 PostgreSQL date 범위 밖이다. */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);

    private final BillingRepository billingRepository;
    private final com.dlab.domain.payment.repository.PaymentTransactionRepository transactionRepository;
    private final ExcelExporter excelExporter;

    /**
     * 수납현황 목록.
     *
     * @param unpaidOnly {@code true}면 미납 건만. 미납자 추출·독촉이 이걸 쓴다
     */
    @Transactional(readOnly = true)
    public List<Row> find(AuthPrincipal me, Long academyId, short year,
                          LocalDate from, LocalDate to, BillingType type, boolean unpaidOnly) {
        return find(me, academyId, year, from, to,
                type == null ? null : List.of(type), unpaidOnly, null, null);
    }

    /**
     * 화면 조건 전부로 조회.
     *
     * @param types   청구 항목 여럿. 화면이 체크박스로 여러 개를 고른다 — 하나만 받으면
     *                교습비·급식비를 같이 보려는 화면이 두 번 호출해 합계가 어긋난다
     * @param keyword 이름·학번·전표번호 통합 검색
     * @param method  결제수단. <b>취소된 거래는 세지 않는다</b> — 취소분까지 보면
     *                "카드로 낸 사람" 목록에 환불된 건이 섞인다
     */
    public List<Row> find(AuthPrincipal me, Long academyId, short year,
                          LocalDate from, LocalDate to, List<BillingType> types,
                          boolean unpaidOnly, String keyword,
                          com.dlab.domain.payment.entity.PaymentMethod method) {

        Long scope = requireScope(me, academyId);
        // ★ null을 그대로 넘기지 않는다. PostgreSQL이 ":param IS NULL"만 보고는
        //   타입을 추론하지 못해 쿼리가 깨진다 — 넓은 기본값으로 바꿔 넘긴다
        LocalDate start = from != null ? from : EARLIEST;
        LocalDate end = to != null ? to : LATEST;

        List<Object[]> raw = billingRepository.findWithReceived(scope, year, start, end);

        // 청구마다 거래를 조회하면 쿼리가 행 수만큼 나간다. ID를 모아 한 번에 읽는다
        List<Long> billingIds = raw.stream().map(r -> ((Billing) r[0]).getId()).toList();
        Map<Long, List<com.dlab.domain.payment.entity.PaymentTransaction>> paymentsByBilling =
                billingIds.isEmpty() ? Map.of()
                        : transactionRepository.findActiveByBillingIds(billingIds).stream()
                                .collect(Collectors.groupingBy(t -> t.getBilling().getId()));

        return raw.stream()
                .map(r -> {
                    Billing b = (Billing) r[0];
                    return Row.of(b, ((Number) r[1]).intValue(),
                            paymentsByBilling.getOrDefault(b.getId(), List.of()));
                })
                .filter(row -> types == null || types.isEmpty()
                        || types.contains(row.billing().getBillingType()))
                .filter(row -> !unpaidOnly || row.unpaid() > 0)
                .filter(row -> matches(row, keyword))
                .filter(row -> method == null || row.payments().stream()
                        .anyMatch(t -> t.getMethod() == method))
                .toList();
    }

    /**
     * 합계.
     *
     * <p><b>목록과 같은 조건으로 계산한다</b> — 화면에 뜬 목록과 합계가 다르면
     * 데스크가 어느 쪽을 믿어야 할지 알 수 없다.
     */
    @Transactional(readOnly = true)
    private static boolean matches(Row row, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim();
        var enrollment = row.billing().getEnrollment();
        return contains(enrollment.getStudentNo(), kw)
                || contains(enrollment.getStudent().getName(), kw)
                || contains(row.billing().getName(), kw)
                // 전표번호로 찾는 문의가 실제로 온다 — 학부모가 영수증 번호를 들고 전화한다
                || row.payments().stream().anyMatch(t -> contains(t.getPgTid(), kw));
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    public Summary summarize(AuthPrincipal me, Long academyId, short year,
                             LocalDate from, LocalDate to, BillingType type) {
        return summarize(me, academyId, year, from, to,
                type == null ? null : List.of(type), null, null);
    }

    /** 합계. <b>목록과 같은 조건을 쓴다</b> — 다르면 데스크가 어느 쪽을 믿을지 알 수 없다. */
    public Summary summarize(AuthPrincipal me, Long academyId, short year,
                             LocalDate from, LocalDate to, List<BillingType> types,
                             String keyword,
                             com.dlab.domain.payment.entity.PaymentMethod method) {

        List<Row> rows = find(me, academyId, year, from, to, types, false, keyword, method);

        Map<BillingType, Integer> unpaidByType = rows.stream()
                .filter(r -> r.unpaid() > 0)
                .collect(Collectors.groupingBy(r -> r.billing().getBillingType(),
                        Collectors.summingInt(Row::unpaid)));

        return new Summary(
                rows.size(),
                rows.stream().mapToInt(r -> r.billing().getBilledAmount()).sum(),
                rows.stream().mapToInt(Row::received).sum(),
                rows.stream().mapToInt(Row::unpaid).sum(),
                (int) rows.stream().filter(r -> r.unpaid() > 0).count(),
                unpaidByType);
    }

    /**
     * 엑셀 내려받기.
     *
     * <p>⚠️ <b>연락처는 마스킹된다</b>(실행가이드 3.2, 기본 ON). 마스킹된 값을 그대로
     * 다시 올리면 <b>전 학생 연락처가 마스킹 문자열로 덮어써진다</b> — Import 쪽이
     * 같은 판정으로 걸러내지만, Export 단계에서 규칙을 바꾸면 그 방어가 깨진다.
     */
    @Transactional(readOnly = true)
    public byte[] export(AuthPrincipal me, Long academyId, short year,
                         LocalDate from, LocalDate to, BillingType type, boolean unpaidOnly) {
        return export(me, academyId, year, from, to,
                type == null ? null : List.of(type), unpaidOnly, null, null);
    }

    /**
     * 화면 조건 그대로 내려받는다 — 조건을 못 받으면 화면에서 좁혀 놓고 받은 파일에
     * 전체가 담긴다.
     */
    public byte[] export(AuthPrincipal me, Long academyId, short year,
                         LocalDate from, LocalDate to, List<BillingType> types,
                         boolean unpaidOnly, String keyword,
                         com.dlab.domain.payment.entity.PaymentMethod method) {

        List<Row> rows = find(me, academyId, year, from, to, types, unpaidOnly, keyword, method);
        List<String> headers = List.of("학번", "이름", "연락처", "청구항목", "유형",
                "이용월", "청구액", "수납액", "미납액", "납부기한", "상태");

        byte[] bytes = excelExporter.export("수납현황", headers, rows, row -> {
            Billing b = row.billing();
            var student = b.getEnrollment().getStudent();
            return java.util.Arrays.asList(
                    b.getEnrollment().getStudentNo(),
                    student.getName(),
                    Masking.phone(student.getPhone()),
                    b.getName(),
                    b.getBillingType().name(),
                    b.getServiceMonth() == null ? ""
                            : "%d-%02d".formatted(b.getServiceYear(), b.getServiceMonth()),
                    String.valueOf(b.getBilledAmount()),
                    String.valueOf(row.received()),
                    String.valueOf(row.unpaid()),
                    b.getDueDate() == null ? "" : b.getDueDate().toString(),
                    b.getStatus().name());
        });

        log.info("수납현황 엑셀: academyId={}, year={}, {}건 (연락처 마스킹)",
                academyId, year, rows.size());
        return bytes;
    }

    private Long requireScope(AuthPrincipal me, Long academyId) {
        return me.requireAcademyScope(academyId);
    }

    /**
     * @param received 취소되지 않은 수납 합계
     * @param payments 살아 있는 수납 거래. <b>언제 무엇으로 냈는지</b>는 청구 한 줄로는
     *                 알 수 없어 함께 내린다 — 화면 상단의 결제수단별 집계가 이 값을 쓴다
     */
    public record Row(Billing billing, int received,
                      List<com.dlab.domain.payment.entity.PaymentTransaction> payments) {

        static Row of(Billing billing, int received,
                      List<com.dlab.domain.payment.entity.PaymentTransaction> payments) {
            return new Row(billing, received, payments);
        }

        /** 과납이어도 음수로 내려가지 않는다 — 미납액 합계가 줄어들면 안 된다. */
        public int unpaid() {
            return Math.max(0, billing.getBilledAmount() - received);
        }
    }

    /**
     * @param unpaidCount  미납 <b>건수</b>다. 학생 수가 아니다 — 한 학생이 여러 달 밀릴 수 있다
     * @param unpaidByType 유형별 미납액. 교습비 미납과 급식비 미납은 독촉 방식이 다르다
     */
    public record Summary(int count, int billedAmount, int receivedAmount, int unpaidAmount,
                          int unpaidCount, Map<BillingType, Integer> unpaidByType) {
    }
}
