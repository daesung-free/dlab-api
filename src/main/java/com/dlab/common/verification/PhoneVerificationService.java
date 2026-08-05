package com.dlab.common.verification;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * 휴대폰 본인인증 (앱 요구사항 A-2).
 *
 * <p><b>2단계다.</b> ①번호로 인증번호를 받고 ②맞히면 <b>인증 토큰</b>을 받는다.
 * 가입 요청에는 번호가 아니라 이 토큰을 실어 보낸다 — 번호만 받으면 인증을 건너뛰고
 * 아무 번호로나 가입할 수 있다.
 *
 * <p>인증하는 것은 <b>가입하는 본인의 번호</b>다. 학부모 가입에서 학생 번호를 인증하는 게
 * 아니다 — 자녀 연결은 학생 고유ID가 담당하고, 이쪽은 본인확인·비밀번호 찾기 수단·
 * 알림톡 수신처 확보가 목적이다(2026-08-05 시트 A-2 확정).
 *
 * <p><b>Redis에 둔다.</b> 몇 분짜리 값이라 TTL로 만료를 맡길 수 있고, 지우는 게 곧 무효화다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    private static final String CODE_KEY = "verify:code:";
    private static final String ATTEMPT_KEY = "verify:try:";
    private static final String TOKEN_KEY = "verify:token:";

    /** 인증번호 유효시간. 짧으면 문자 지연에 걸리고 길면 탈취 창이 넓어진다. */
    private static final Duration CODE_TTL = Duration.ofMinutes(3);

    /**
     * 인증 완료 토큰 유효시간.
     *
     * <p>인증 후 이름·비밀번호·학생 고유ID를 입력할 시간이다. 3분이면 고유ID를 찾다가
     * 만료된다 — 학생 마이페이지를 열어 확인해야 하는 값이라 시간이 걸린다.
     */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /** 인증번호 시도 횟수. 6자리는 100만분의 1이지만 무제한이면 결국 뚫린다. */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SmsSender smsSender;

    /**
     * 인증번호 발송.
     *
     * <p>이미 보낸 번호로 다시 요청하면 <b>새 번호로 덮어쓴다</b>. 재발송이 흔한 흐름이라
     * 옛 번호를 살려두면 어느 쪽이 맞는지 사용자도 서버도 헷갈린다.
     */
    public void requestCode(String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(CODE_KEY + phone, code, CODE_TTL);
        // 재발송했는데 이전 시도 횟수가 남아 있으면 곧바로 한도에 걸린다
        redis.delete(ATTEMPT_KEY + phone);
        smsSender.sendVerificationCode(phone, code);
    }

    /**
     * 인증번호 확인 → 인증 토큰 발급.
     *
     * @return 가입 요청에 실어 보낼 인증 토큰
     */
    public String confirm(String phone, String code) {
        String saved = redis.opsForValue().get(CODE_KEY + phone);
        if (saved == null) {
            // 발송한 적이 없거나 만료됐다. 둘을 구분해줄 실익이 없다.
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        Long attempts = redis.opsForValue().increment(ATTEMPT_KEY + phone);
        if (attempts != null && attempts == 1L) {
            redis.expire(ATTEMPT_KEY + phone, CODE_TTL);
        }
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            // 인증번호까지 버린다 — 남겨두면 재요청 없이 계속 두드릴 수 있다
            redis.delete(CODE_KEY + phone);
            throw new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!saved.equals(code)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_MISMATCH);
        }

        redis.delete(CODE_KEY + phone);
        redis.delete(ATTEMPT_KEY + phone);

        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(TOKEN_KEY + token, phone, TOKEN_TTL);
        return token;
    }

    /**
     * 인증 토큰을 검증하고 <b>소비한다</b>(재사용 불가).
     *
     * <p>재사용을 막지 않으면 토큰 하나로 여러 계정을 만들 수 있다.
     *
     * @return 인증된 전화번호. 실명·생년월일은 알 수 없다({@link VerifiedIdentity} 참고)
     */
    public VerifiedIdentity consume(String token) {
        String phone = redis.opsForValue().get(TOKEN_KEY + token);
        if (phone == null) {
            throw new BusinessException(ErrorCode.PHONE_NOT_VERIFIED);
        }
        redis.delete(TOKEN_KEY + token);
        return new VerifiedIdentity(phone);
    }
}
