package com.dlab.common.privacy;

import java.util.regex.Pattern;

/**
 * 개인정보 마스킹 (실행가이드 3.2).
 *
 * <p>엑셀 다운로드는 <b>마스킹 기본 ON</b>이다 — 교무업무 명단·학생 관리 Export 공통.
 * 발주처가 개인정보 유출 조사·벌금 사례로 민감도가 높은 상태이므로 임의로 완화하지 않는다.
 *
 * <p><b>★ 마스킹 판정({@link #isMasked})이 Export만큼 중요하다.</b> 실무에서 가장 흔한 흐름이
 * "내려받아 수정 후 재업로드"인데, 내려받은 파일의 연락처는 이미 {@code 010-****-1234}다.
 * 이걸 그대로 저장하면 <b>전 학생 연락처가 마스킹 문자열로 덮어써진다.</b> 그러면 미등원
 * 알림톡이 학부모에게 가지 않는데, 화면에는 원래도 마스킹돼 보이니 한동안 아무도 눈치채지 못한다.
 * 그래서 업로드 쪽에서 마스킹된 값을 <b>"이 칸은 안 건드렸다"로 읽어</b> 기존 값을 유지한다.
 */
public final class Masking {

    /** 마스킹 문자. 가운데 자리를 통째로 가린다. */
    private static final String MASK = "****";

    /**
     * 마스킹된 값 판정.
     *
     * <p><b>{@code *}가 하나라도 있으면 마스킹으로 본다.</b> 연속 2개 이상으로 좁히면
     * <b>이름 마스킹({@code 김*수})이 빠져나간다</b> — 그러면 이름이 {@code 김*수}로 저장된다.
     * 실제 이름·연락처·학교명에 {@code *}가 들어갈 일은 없으므로 넓게 잡는 쪽이 안전하다.
     */
    private static final Pattern MASKED = Pattern.compile(".*\\*.*");

    private Masking() {
    }

    /** {@code 010-1234-5678} → {@code 010-****-5678}. 형식이 달라도 가운데를 가린다. */
    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            // 번호로 보기 어려운 값. 통째로 가린다 — 원본을 흘리지 않는 쪽이 안전하다.
            return MASK;
        }
        String head = digits.substring(0, 3);
        String tail = digits.substring(digits.length() - 4);
        return "%s-%s-%s".formatted(head, MASK, tail);
    }

    /** 이름 가운데 글자를 가린다. {@code 김철수} → {@code 김*수}, 두 글자는 {@code 김*}. */
    public static String name(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /**
     * 이 값이 마스킹된 것인가.
     *
     * <p>업로드에서 이 판정이 {@code true}면 <b>기존 값을 유지</b>한다(수정), 또는
     * <b>오류 처리</b>한다(신규 등록 — 유지할 원래 값이 없다).
     */
    public static boolean isMasked(String value) {
        return value != null && MASKED.matcher(value).matches();
    }
}
