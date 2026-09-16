package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.payment.repository.PaymentRequestRepository;
import com.dlab.domain.payment.repository.PgSiteRepository;
import com.dlab.integration.pg.KcpBuyLinkClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 요청·완료 처리 (바이링크).
 *
 * <h2>흐름</h2>
 * <pre>
 *   데스크가 청구를 고르고 "결제 링크 보내기"
 *     → 우리가 KCP 에 URL 생성 요청 (사이트코드는 지점·용도·채널로 고른다)
 *     → KCP 가 학부모에게 문자 발송
 *     → 학부모가 결제
 *     → KCP → 우리 Webhook → 수납 확정
 * </pre>
 *
 * <h2>★★ 완료는 Webhook 만 확정한다</h2>
 * 생성 응답이 성공이어도 결제된 것이 아니다. 그 자리에서 수납을 잡으면 <b>링크만 받고
 * 결제하지 않은 학생이 완납으로 기록된다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestService {

    private static final DateTimeFormatter EXPIRE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 가상계좌 입금 기한은 초까지 준다 */
    private static final DateTimeFormatter VBANK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 링크 유효기간. 너무 길면 지난 달 청구가 살아 있고, 짧으면 학부모가 못 낸다 */
    private static final int EXPIRE_DAYS = 7;

    private final BillingRepository billingRepository;
    private final PgSiteRepository pgSiteRepository;
    private final PaymentRequestRepository requestRepository;
    private final KcpBuyLinkClient client;
    private final Clock clock;

    /**
     * 결제 링크 생성 + 문자 발송.
     *
     * @param sendSms KCP 가 문자를 대신 보낼지. 끄면 URL 만 받아 화면에 띄운다
     */
    @Transactional
    public PaymentRequest createBuyLink(AuthPrincipal me, Long billingId, PayMethod payMethod,
                                        boolean sendSms) {
        Billing billing = requireBillable(me, billingId);
        int amount = billing.unpaidAmount();
        PgSite site = requireSite(billing, PgChannel.BUYLINK);

        var student = billing.getEnrollment().getStudent();
        String orderNo = orderNo(billing);
        PaymentRequest request = requestRepository.save(
                new PaymentRequest(billing, site, orderNo, amount, payMethod));

        LocalDate expire = LocalDate.now(clock).plusDays(EXPIRE_DAYS);
        var created = client.createPayUrl(new KcpBuyLinkClient.CreateCommand(
                site.getSiteCd(), site.getMgmtId(), orderNo, amount, payMethod.name(),
                // ★ 상품명에 학생 이름을 넣지 않는다 — 문자가 다른 사람에게 전달될 수 있다
                billing.getName(),
                student.getName(), digits(student.getPhone()), null,
                expire.format(EXPIRE_FORMAT), sendSms));

        request.markUrlCreated(created.payUrl(), created.urlRegId(),
                expire.atStartOfDay(clock.getZone()).toInstant());
        log.info("결제 링크 생성: billingId={}, orderNo={}, 금액={}", billingId, orderNo, amount);
        return request;
    }

    /**
     * 가상계좌 발급.
     *
     * <p>⚠️ <b>발급은 결제가 아니다.</b> 계좌번호가 나왔을 뿐이고, 입금은 며칠 뒤에 들어오거나
     * 영영 안 들어온다 — 완료는 입금 통보 Webhook 이 확정한다. 화면도 "발급됨" 으로 보여야
     * 데스크가 받은 줄 알지 않는다.
     *
     * @param bankCode 입금받을 은행 코드(KCP 은행코드표)
     * @param days     입금 기한. 지나면 그 계좌로 낼 수 없다
     */
    @Transactional
    public PaymentRequest issueVbank(AuthPrincipal me, Long billingId, String bankCode, int days) {
        Billing billing = requireBillable(me, billingId);
        int amount = billing.unpaidAmount();

        PgSite site = requireSite(billing, PgChannel.VBANK);
        var student = billing.getEnrollment().getStudent();
        String orderNo = orderNo(billing);

        PaymentRequest request = requestRepository.save(
                new PaymentRequest(billing, site, orderNo, amount, PayMethod.VCNT));

        // 기한 끝은 그날 23:59:59 다 — 자정으로 두면 그 하루가 통째로 빠진다
        var expire = LocalDate.now(clock).plusDays(days).atTime(23, 59, 59);
        var issued = client.issueVbank(new KcpBuyLinkClient.VbankCommand(
                site.getSiteCd(), orderNo, amount, billing.getName(),
                student.getName(), digits(student.getPhone()), bankCode,
                expire.format(VBANK_DATE_FORMAT)));

        request.markVbankIssued(issued.tno(), issued.account(), issued.bankName(),
                issued.bankCode(), issued.depositor(),
                expire.atZone(clock.getZone()).toInstant());
        log.info("가상계좌 발급: billingId={}, orderNo={}, 금액={}", billingId, orderNo, amount);
        return request;
    }

    /**
     * 가상계좌 입금 통보.
     *
     * <p>바이링크와 같은 경로로 확정한다 — 다른 것은 <b>입금자명</b>이 따로 온다는 것뿐이다.
     *
     * <p>⚠️ <b>입금자가 학생·학부모와 다를 수 있다.</b> 조부모가 대신 내는 경우가 흔해서
     * 이름을 대조해 반려하면 정상 입금이 막힌다 — 기록만 남긴다.
     */
    @Transactional
    public boolean confirmVbankDeposit(String orderNo, String tno, int amount, String remitter) {
        PaymentRequest request = requestRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "결제 요청을 찾을 수 없습니다: " + orderNo));
        request.recordRemitter(remitter);
        return confirm(orderNo, tno, amount, "가상계좌 입금" + (remitter == null ? "" : " · " + remitter));
    }

    /**
     * 단말기(POS) 승인 결과 기록.
     *
     * <h2>★ 여기서는 KCP 를 부르지 않는다</h2>
     * 단말 승인은 <b>데스크 PC 에서 이미 끝난 일</b>이다. SecureVCAT 이 설치된 PC 에서
     * 브라우저가 단말을 직접 호출해 승인받고, 우리는 <b>그 결과를 받아 적기만</b> 한다.
     * 우리가 다시 KCP 에 요청하면 같은 금액이 두 번 승인된다.
     *
     * <p>그래서 바이링크·가상계좌와 달리 <b>Webhook 을 기다리지 않는다</b> — 호출 시점에
     * 이미 승인된 거래라 바로 수납으로 잡는다.
     *
     * <p><b>승인번호로 멱등을 건다.</b> 화면이 저장에 실패해 다시 누르면 같은 승인이 두 번
     * 기록되고, 그 학생은 두 번 낸 것으로 남는다.
     *
     * @param approvalNo 단말이 준 승인번호. 이 값이 중복 방지의 근거다
     */
    @Transactional
    public PaymentRequest recordTerminalApproval(AuthPrincipal me, Long billingId, int amount,
                                                 String approvalNo, String cardName,
                                                 Instant approvedAt) {
        // ★ 중복부터 본다. 뒤에 두면 첫 저장으로 완납이 된 청구가 "이미 완납" 으로 거절되어,
        //   화면은 같은 승인을 저장하지 못한 것으로 읽고 다시 누른다.
        var existing = requestRepository.findByTno(approvalNo);
        if (existing.isPresent()) {
            log.info("단말 승인 중복 저장 차단: approvalNo={}", approvalNo);
            return existing.get();
        }

        Billing billing = requireBillable(me, billingId);
        if (amount > billing.unpaidAmount()) {
            // 미납액보다 많이 받으면 과납이 되는데, 환불 경로가 따로 필요해진다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "미납액(%,d원)보다 많은 금액은 기록할 수 없습니다.".formatted(billing.unpaidAmount()));
        }

        // 단말 채널 사이트코드는 있으면 함께 남긴다 — 정산 대사에 쓰인다.
        // 없다고 막지 않는다. 승인은 이미 끝났고, 기록을 거절하면 받은 돈이 사라진다
        PgSite site = siteOrNull(billing, PgChannel.TERMINAL);
        if (site == null) {
            throw new BusinessException(ErrorCode.PG_SITE_NOT_FOUND,
                    "단말기 사이트코드가 등록되지 않았습니다. 등록 후 다시 저장해 주세요.");
        }

        PaymentRequest request = requestRepository.save(
                new PaymentRequest(billing, site, orderNo(billing), amount, PayMethod.CARD));
        request.markPaid(approvalNo, approvedAt == null ? Instant.now(clock) : approvedAt,
                cardName);
        billing.addPayment(amount, PaymentMethod.CARD,
                approvedAt == null ? Instant.now(clock) : approvedAt);

        log.info("단말 승인 기록: billingId={}, 승인번호={}, 금액={}", billingId, approvalNo, amount);
        return request;
    }

    /**
     * Webhook 수신 — 여기서만 수납이 확정된다.
     *
     * <p><b>멱등해야 한다.</b> KCP 는 우리가 {@code result=0000} 을 돌려줄 때까지 최대 10번
     * 재전송한다. 두 번 처리하면 그 학생이 두 번 낸 것으로 기록된다.
     *
     * @return 이번 호출에서 처음 확정됐는지
     */
    @Transactional
    public boolean confirm(String orderNo, String tno, int amount, String payDetail) {
        PaymentRequest request = requestRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "결제 요청을 찾을 수 없습니다: " + orderNo));

        // 금액이 다르면 멈춘다 — 우리가 청구한 금액과 실제 승인액이 갈리면 수납을 잡을 수 없다
        if (request.getAmount() != amount) {
            request.markFailed("승인 금액 불일치: 요청 %d / 승인 %d".formatted(request.getAmount(), amount));
            log.error("결제 금액 불일치: orderNo={}, 요청={}, 승인={}", orderNo, request.getAmount(), amount);
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED, "승인 금액이 요청과 다릅니다.");
        }

        boolean first = request.markPaid(tno, Instant.now(clock), payDetail);
        if (!first) {
            log.info("결제 Webhook 재전송 — 이미 처리됨: orderNo={}, tno={}", orderNo, tno);
            return false;
        }

        Billing billing = request.getBilling();
        billing.addPayment(amount, methodOf(request.getPayMethod()), Instant.now(clock));
        log.info("결제 완료: orderNo={}, tno={}, billingId={}", orderNo, tno, billing.getId());
        return true;
    }

    private PgSite siteOrNull(Billing billing, PgChannel channel) {
        PgPurpose purpose = billing.getBillingType() == BillingType.MEAL
                ? PgPurpose.MEAL : PgPurpose.TUITION;
        return pgSiteRepository.findForUse(billing.getAcademy().getId(), purpose, channel)
                .orElse(null);
    }

    /** 결제할 수 있는 청구인지. 취소·완납 건에 요청하면 학부모가 두 번 낸다. */
    private Billing requireBillable(AuthPrincipal me, Long billingId) {
        Billing billing = billingRepository.findById(billingId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "청구를 찾을 수 없습니다."));
        if (!me.canAccessAcademy(billing.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (billing.getStatus() == BillingStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "취소된 청구입니다.");
        }
        if (billing.unpaidAmount() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 완납된 청구입니다.");
        }
        return billing;
    }

    /**
     * 쓸 사이트코드.
     *
     * <p>★ <b>급식비는 업체 명의 코드로 나간다.</b> 학원 코드로 받으면 그 돈이 업체에게
     * 가지 않고 정산·환불 주체가 어긋난다.
     */
    private PgSite requireSite(Billing billing, PgChannel channel) {
        PgPurpose purpose = billing.getBillingType() == BillingType.MEAL
                ? PgPurpose.MEAL : PgPurpose.TUITION;
        return pgSiteRepository
                .findForUse(billing.getAcademy().getId(), purpose, channel)
                .orElseThrow(() -> new BusinessException(ErrorCode.PG_SITE_NOT_FOUND,
                        "%s %s 사이트코드가 등록되지 않았습니다.".formatted(purpose, channel)));
    }

    /**
     * 우리 주문번호. KCP 가 이 값으로 돌아온다.
     *
     * <p>청구 id 만으로는 안 된다 — 한 청구에 링크를 다시 보내는 경우가 있고, 주문번호가
     * 겹치면 어느 요청의 결과인지 알 수 없다. 뒤에 시각을 붙여 매번 새로 만든다.
     */
    private String orderNo(Billing billing) {
        return "B%d-%d".formatted(billing.getId(), Instant.now(clock).toEpochMilli());
    }

    /** KCP 결제수단 → 우리 수납 수단. 계좌이체·휴대폰은 현금성으로 잡는다 */
    private PaymentMethod methodOf(PayMethod payMethod) {
        return switch (payMethod) {
            case CARD -> PaymentMethod.CARD;
            case VCNT -> PaymentMethod.VBANK;
            // 계좌이체·휴대폰은 현금성으로 잡는다
            default -> PaymentMethod.TRANSFER;
        };
    }

    /** 휴대폰번호는 숫자만 넘긴다 — 하이픈이 들어가면 KCP 가 거절한다 */
    private String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }
}
