package com.dlab.common.privacy;

import java.time.LocalDate;

/**
 * 개인정보 마스킹 (실행가이드 3.2).
 *
 * <p><b>마스킹은 기본값이다.</b> 엑셀 다운로드·목록 응답은 마스킹된 값을 내리고,
 * 원본은 상위 관리자 권한이 확인된 경로에서만 노출한다({@link PersonalDataPolicy}).
 * 발주처가 개인정보 유출 조사를 겪은 상태라 임의로 완화하지 않는다.
 *
 * <p><b>자릿수를 보존한다.</b> {@code 010-1234-5678} → {@code 010-****-5678}처럼
 * 뒷자리를 남기는 이유는, 운영자가 명단에서 동명이인을 구분하고 통화 대상을 확인하는 데
 * 뒷 4자리를 실제로 쓰기 때문이다. 전부 가리면 명단 자체가 쓸모없어져 결국
 * 마스킹을 끄게 된다.
 */
public final class Masking {

    private static final String MASKED = "****";

    private Masking() {
    }

    /**
     * 전화번호. {@code 010-1234-5678} → {@code 010-****-5678}.
     *
     * <p>하이픈이 없거나 자릿수가 다른 값도 들어온다(레거시 이관분). 그래서 하이픈 위치를
     * 믿지 않고 <b>숫자만 뽑아 국번을 다시 판별한다</b> — 서울 {@code 02}는 2자리, 나머지는
     * 3자리다. 일괄로 3자리를 떼면 {@code 02-123-4567}이 {@code 021-}로 나와
     * 없는 번호처럼 보인다.
     *
     * <p>판별이 안 되는 값은 전부 가린다. 파싱에 실패해 원본이 그대로 나가는 것보다
     * 과하게 가리는 쪽이 안전하다.
     */
    public static String phone(String phone) {
        if (isBlank(phone)) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        int prefixLength = digits.startsWith("02") ? 2 : 3;
        if (digits.length() < prefixLength + 5) {
            return MASKED;
        }
        String prefix = digits.substring(0, prefixLength);
        String tail = digits.substring(digits.length() - 4);
        int middleLength = digits.length() - prefixLength - 4;
        return prefix + "-" + "*".repeat(middleLength) + "-" + tail;
    }

    /** 이름. {@code 김민지} → {@code 김*지}, {@code 이준} → {@code 이*}. */
    public static String name(String name) {
        if (isBlank(name)) {
            return name;
        }
        String trimmed = name.trim();
        if (trimmed.length() == 1) {
            return trimmed;
        }
        if (trimmed.length() == 2) {
            return trimmed.charAt(0) + "*";
        }
        return trimmed.charAt(0)
                + "*".repeat(trimmed.length() - 2)
                + trimmed.charAt(trimmed.length() - 1);
    }

    /**
     * 생년월일 → {@code 2007-**-**}.
     *
     * <p>연도를 남기는 건 학년 확인에 쓰이기 때문이다. 월·일이 있으면 주민번호 앞자리가
     * 사실상 복원되므로 가린다.
     */
    public static String birthDate(LocalDate birthDate) {
        return birthDate == null ? null : birthDate.getYear() + "-**-**";
    }

    /** 주소. 시/군/구까지만 남긴다. */
    public static String address(String address) {
        if (isBlank(address)) {
            return address;
        }
        String[] parts = address.trim().split("\\s+");
        if (parts.length <= 2) {
            return parts[0] + " ***";
        }
        return parts[0] + " " + parts[1] + " ***";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
