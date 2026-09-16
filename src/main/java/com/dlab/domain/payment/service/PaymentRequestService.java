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
        int amount = billing.unpaidAmount();
        if (amount <= 0) {
            // 완납 건에 링크를 또 보내면 학부모가 두 번 낸다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 완납된 청구입니다.");
        }

        // ★ 급식비는 업체 명의 사이트코드로 나간다. 학원 코드로 받으면 그 돈이 업체에게 안 간다
        PgPurpose purpose = billing.getBillingType() == BillingType.MEAL
                ? PgPurpose.MEAL : PgPurpose.TUITION;
        PgSite site = pgSiteRepository
                .findForUse(billing.getAcademy().getId(), purpose, PgChannel.BUYLINK)
                .orElseThrow(() -> new BusinessException(ErrorCode.PG_SITE_NOT_FOUND,
                        "%s 바이링크 사이트코드가 등록되지 않았습니다.".formatted(purpose)));

        var student = billing.getEnrollment().getStudent();
        String orderNo = orderNo(billing);
        PaymentRequest request = requestRepository.save(
                new PaymentRequest(billing, site, orderNo, amount, payMethod));

        LocalDate expire = LocalDate.now(clock).plusDays(EXPIRE_DAYS);
        var created = client.createPayUrl(new KcpBuyLinkClient.CreateCommand(
                site.getSiteCd(), orderNo, amount, payMethod.name(),
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
        return payMethod == PayMethod.CARD ? PaymentMethod.CARD : PaymentMethod.TRANSFER;
    }

    /** 휴대폰번호는 숫자만 넘긴다 — 하이픈이 들어가면 KCP 가 거절한다 */
    private String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }
}
