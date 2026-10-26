package com.dlab.integration.fcm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Firebase Cloud Messaging 연동 설정.
 *
 * <p>⚠️ <b>비어 있어도 앱은 뜬다.</b> 자격증명이 없으면 발송기 빈이 만들어지지 않고,
 * 발송 경로가 {@code SKIPPED "발송 구현체 없음: FCM_PUSH"} 로 남긴다 —
 * 「설정이 덜 됐다」와 「보냈는데 실패했다」가 구분된다(알림톡과 같은 규약).
 *
 * @param credentialsPath 서비스 계정 키(JSON) 파일 경로. ⚠️ <b>파일 자체를 커밋하지 말 것</b>(§8).
 *                        콘솔의 「프로젝트 설정 → 서비스 계정 → 새 비공개 키 생성」으로 받은 것이고
 *                        <b>private key 가 들어 있다.</b>
 *                        ★ 구글이 닫은 legacy 서버 키(문자열 하나)가 아니다 — HTTP v1 은
 *                        이 JSON 으로 OAuth2 토큰을 받아 호출한다.
 * @param dryRun          {@code true} 면 FCM 에 검증만 요청하고 단말로 보내지 않는다.
 *                        알리고 {@code testmode_yn} 과 같은 쓰임이다.
 */
@ConfigurationProperties(prefix = "fcm")
public record FcmProperties(String credentialsPath, Boolean dryRun) {

    public boolean configured() {
        return credentialsPath != null && !credentialsPath.isBlank();
    }

    /**
     * 검증 전용 여부.
     *
     * <p><b>기본값을 꺼 둔다.</b> 알림톡과 반대인데 이유가 있다 — 푸시는 과금이 없고,
     * 켜 두면 「보냈는데 안 온다」를 디버깅할 때 원인이 이 설정인 줄 모른다.
     */
    public boolean dryRunOrDefault() {
        return dryRun != null && dryRun;
    }
}
