package com.dlab.common.security;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 비밀번호 정책 + 임시 비밀번호 발급.
 *
 * <p><b>⚠️ 이 값들은 확정된 정책이 아니다.</b> 앱 요구사항 A-1이
 * <i>"비밀번호 정책·로그인 실패 5회 계정 잠금(4시트 NF-05·NF-06)"</i>을 참조하는데,
 * <b>NF-05·NF-06이 요구사항정의서·실행가이드 어느 탭에도 존재하지 않는다</b>(2026-08-05 전수 검색).
 * 끊긴 참조라 아래는 최소 기준으로 잡은 잠정값이다 — 정책이 오면 <b>이 파일의 상수만</b> 바꾼다.
 *
 * <p>확정된 것은 <b>실패 5회</b>(A-1 본문)와 <b>잠금 해제는 관리자</b>(F-4.12-1에 관리자 기능으로
 * 명시)뿐이다. 자동 해제를 넣지 않은 이유가 이것이다 — 시간이 지나 저절로 풀리면
 * 관리자 잠금 해제 화면이 있을 이유가 없다.
 */
public final class PasswordPolicy {

    /** 로그인 실패 허용 횟수. 이 횟수째 실패에서 잠긴다. (A-1 "5회") */
    public static final int MAX_LOGIN_FAILURES = 5;

    /**
     * 실패 카운터 유지 시간.
     *
     * <p>잠금 자체는 영구(관리자 해제)지만 <b>카운터는 만료시킨다</b> — 한 달 전에 두 번
     * 틀린 것이 오늘의 세 번째 실패와 합산돼 잠기면, 사용자는 자기가 왜 잠겼는지 알 수 없다.
     */
    public static final java.time.Duration FAILURE_WINDOW = java.time.Duration.ofMinutes(30);

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    /** 영문·숫자를 각각 하나 이상. 특수문자 필수 여부는 정책 미확정이라 요구하지 않는다. */
    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    /**
     * 임시 비밀번호 문자 집합.
     *
     * <p><b>{@code 0/O}, {@code 1/l/I}를 뺐다.</b> 임시 비밀번호는 관리자가 전화·구두로
     * 불러주는 값이라, 혼동되는 글자가 섞이면 "안 된다"는 문의가 그대로 발생한다.
     */
    private static final String TEMP_ALPHABET = "abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TEMP_LENGTH = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    /**
     * 정책 위반이면 예외를 던진다.
     *
     * @throws BusinessException {@link ErrorCode#PASSWORD_POLICY_VIOLATION}
     */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        // BCrypt는 72바이트를 넘으면 뒤를 조용히 버린다 — 길이 상한을 두지 않으면
        // 사용자는 긴 비밀번호를 설정했다고 믿지만 실제로는 앞부분만 검사된다.
        if (rawPassword.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "비밀번호는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (!HAS_LETTER.matcher(rawPassword).matches() || !HAS_DIGIT.matcher(rawPassword).matches()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "비밀번호는 영문과 숫자를 모두 포함해야 합니다.");
        }
    }

    /**
     * 임시 비밀번호 생성. 정책을 반드시 만족하도록 영문·숫자를 한 자씩 심고 섞는다.
     *
     * <p>정책을 만족시키지 않으면, 발급받은 사용자가 그 비밀번호로 로그인은 되는데
     * 변경 화면에서 같은 값을 다시 넣을 수 없는 이상한 상태가 된다.
     */
    public static String generateTemporary() {
        char[] chars = new char[TEMP_LENGTH];
        chars[0] = pick("abcdefghijkmnpqrstuvwxyz");
        chars[1] = pick("23456789");
        for (int i = 2; i < TEMP_LENGTH; i++) {
            chars[i] = pick(TEMP_ALPHABET);
        }
        // 앞 두 자리가 항상 영문·숫자면 패턴이 드러난다
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char pick(String source) {
        return source.charAt(RANDOM.nextInt(source.length()));
    }
}
