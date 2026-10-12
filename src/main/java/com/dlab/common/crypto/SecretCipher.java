package com.dlab.common.crypto;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 저장용 암복호 + 조회용 해시.
 *
 * <h2>★ 해시를 왜 HMAC 으로 하는가 — 이게 핵심이다</h2>
 * 도어락 번호는 <b>네 자리 숫자</b>인 경우가 많다. 그런 값의 {@code SHA-256} 은
 * <b>1만 번만 돌려 보면 되므로 사실상 평문</b>이다 — DB 가 새면 해시 컬럼에서 번호가
 * 그대로 복원된다. 그래서 <b>비밀키를 섞는 HMAC</b> 을 쓴다. 키를 모르면 같은 계산을
 * 할 수 없으므로 전수 대조가 성립하지 않는다.
 *
 * <h2>왜 암호문 말고 해시가 따로 필요한가</h2>
 * 같은 값을 두 번 암호화하면 <b>IV 가 달라 암호문도 달라진다</b>(그게 맞다 — 같으면
 * 같은 번호를 쓴다는 사실이 드러난다). 그래서 <b>암호문끼리는 비교할 수 없고</b>,
 * 「이 번호를 전에 쓴 적 있나」를 묻는 데는 결정적인 값이 하나 더 필요하다.
 *
 * <h2>AES-GCM</h2>
 * 요청마다 새 IV 를 만들어 {@code IV:암호문} 으로 붙여 저장한다. GCM 은 인증 태그가 붙어
 * <b>중간에 바뀌면 복호가 실패</b>한다 — 조용히 다른 값으로 읽히지 않는다.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String SEPARATOR = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(SecretProperties properties) {
        if (!properties.configured()) {
            this.key = null;
            log.warn("⚠️ 저장용 비밀키(SECURITY_SECRET_KEY)가 없다 — 암호화가 필요한 기능은 "
                    + "호출 시 실패한다. openssl rand -base64 32 로 만들어 넣을 것");
            return;
        }
        byte[] raw = Base64.getDecoder().decode(properties.secretKey());
        if (raw.length != 32) {
            // 길이를 조용히 넘기면 AES 가 약한 키로 돌아간다
            throw new IllegalStateException(
                    "SECURITY_SECRET_KEY 는 Base64 로 인코딩된 32바이트여야 합니다. 현재 "
                            + raw.length + "바이트");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean configured() {
        return key != null;
    }

    /** 저장용 암호화. 같은 값을 넣어도 <b>매번 다른 결과</b>가 나온다(IV 가 다르다). */
    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return encoder.encodeToString(iv) + SEPARATOR + encoder.encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("암호화에 실패했습니다.", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        String[] parts = stored.split(SEPARATOR, 2);
        if (parts.length != 2) {
            throw new IllegalStateException("저장 형식이 올바르지 않습니다.");
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])));
            return new String(cipher.doFinal(decoder.decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM 인증 태그가 안 맞으면 여기로 온다 — 값이 바뀌었다는 뜻이다
            throw new IllegalStateException("복호화에 실패했습니다. 저장된 값이 손상됐거나 키가 다릅니다.", e);
        }
    }

    /**
     * 조회·중복 판정용 해시.
     *
     * <p>★ <b>같은 값이면 항상 같은 결과</b>다(그래서 비교에 쓸 수 있다). 그리고
     * <b>비밀키가 섞여</b> 있어서, 키를 모르면 짧은 값이라도 전수 대조가 성립하지 않는다.
     */
    public String hash(String plain) {
        requireKey();
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key.getEncoded(), HMAC));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("해시 계산에 실패했습니다.", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "저장용 비밀키가 설정되지 않아 처리할 수 없습니다. 관리자에게 문의해 주세요.");
        }
    }
}
