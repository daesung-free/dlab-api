package com.dlab.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 저장용 비밀키.
 *
 * <p>DB 에 평문으로 둘 수 없는 값(도어락 번호 등)을 암호화·해시하는 데 쓴다.
 *
 * <p>⚠️ <b>커밋 금지.</b> 운영은 환경변수({@code SECURITY_SECRET_KEY})로 주입한다.
 * 비어 있어도 앱은 뜬다 — 키가 없다고 결제·출결까지 멈추면 안 된다. 암호화가 필요한
 * 기능을 <b>호출할 때</b> 실패한다.
 *
 * <p>★ <b>키를 바꾸면 이미 저장된 값을 읽을 수 없다.</b> 교체가 필요하면 재암호화 절차가
 * 따로 있어야 한다 — 지금은 없으므로 한 번 정하면 유지한다.
 *
 * @param secretKey Base64 인코딩된 32바이트(AES-256). 만들기: {@code openssl rand -base64 32}
 */
@ConfigurationProperties(prefix = "security")
public record SecretProperties(String secretKey) {

    public boolean configured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
