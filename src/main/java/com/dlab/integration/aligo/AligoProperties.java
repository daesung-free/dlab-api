package com.dlab.integration.aligo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알리고(문자·알림톡) 연동 설정.
 *
 * <p>⚠️ <b>비어 있어도 앱은 뜬다.</b> 키가 없으면 목업({@code LoggingSmsSender})이 그대로
 * 남아 문자가 로그로만 찍힌다 — 자격증명이 없는 로컬·CI 를 위해서다.
 *
 * @param apiKey      발급받은 API Key. ⚠️ 커밋 금지(§8)
 * @param userId      알리고 사용자 ID
 * @param senderKey   카카오 <b>발신프로필 키</b>. 알림톡에만 쓰이고 문자에는 쓰이지 않는다 —
 *                    템플릿 코드가 생길 때까지는 들고만 있는다
 * @param senderPhone 발신번호. <b>알리고에 사전 등록된 번호만 쓸 수 있다</b>(02-6105-8090).
 *                    등록 안 된 번호를 넣으면 호출이 실패하는데, 원인이 메시지에만 나온다
 * @param testMode    {@code true} 면 {@code testmode_yn=Y} 로 보낸다
 */
@ConfigurationProperties(prefix = "aligo")
public record AligoProperties(String apiKey, String userId, String senderKey,
                              String senderPhone, Boolean testMode) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank()
                && userId != null && !userId.isBlank()
                && senderPhone != null && !senderPhone.isBlank();
    }

    /**
     * 테스트 모드 여부.
     *
     * <p><b>기본값을 켜 둔다.</b> 키가 꽂힌 환경에서 설정을 빠뜨렸을 때,
     * 실수로 실제 발송·과금이 일어나는 쪽보다 안 나가는 쪽이 되돌리기 쉽다.
     */
    public boolean testModeOrDefault() {
        return testMode == null || testMode;
    }

    /** 알림톡은 발신프로필 키가 더 필요하다. */
    public boolean alimtalkConfigured() {
        return configured() && senderKey != null && !senderKey.isBlank();
    }
}
