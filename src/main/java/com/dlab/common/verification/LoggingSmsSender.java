package com.dlab.common.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 목업 발송기 — 로그로만 남긴다.
 *
 * <p>발송 업체가 확정되기 전까지의 대체물이다(CLAUDE.md §6-3 "확정 전까지 스텁/인터페이스").
 * 실제 구현체가 빈으로 등록되면 {@code @ConditionalOnMissingBean}에 의해 자동으로 물러난다.
 *
 * <p>⚠️ <b>운영에 이 구현체가 올라가면 아무에게도 문자가 가지 않는다.</b> 그런데 API는
 * 200을 돌려주므로 화면상으로는 정상이다 — 기동 시 경고를 남겨 눈에 띄게 한다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "realSmsSender")
public class LoggingSmsSender implements SmsSender {

    public LoggingSmsSender() {
        log.warn("★ SMS 발송이 목업 상태입니다 — 인증번호가 실제로 발송되지 않고 로그로만 남습니다. "
                + "발송 업체 확정 후 SmsSender 구현체를 교체할 것.");
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        // 인증번호는 개인정보가 아니지만 전화번호는 맞다. 뒷자리만 남긴다.
        log.info("[목업 SMS] {} → 인증번호 {}", mask(phone), code);
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "***-****-" + phone.substring(phone.length() - 4);
    }
}
