package com.dlab.domain.appconfig.entity;

/**
 * 앱 버전 비교.
 *
 * <p><b>★ 문자열 비교를 쓰면 안 된다.</b> {@code "1.10.0".compareTo("1.9.0") < 0}이라
 * <b>1.10.0이 1.9.0보다 낮은 것으로 판정</b>된다 — 최소 지원 버전을 1.9.0으로 올린 순간
 * 최신 버전(1.10.x) 사용자 전원이 강제 업데이트 화면에 갇힌다. 두 자리 수로 넘어가는
 * 시점에야 터지므로 초기 테스트로는 안 잡힌다.
 *
 * <p>비교는 <b>마디 단위 숫자</b>로 한다. 마디 개수가 다르면 짧은 쪽을 0으로 채운다
 * ({@code 1.2}와 {@code 1.2.0}은 같다).
 */
public final class AppVersion {

    private AppVersion() {
    }

    /**
     * {@code current}가 {@code minimum}보다 낮은가 — 즉 강제 업데이트 대상인가.
     *
     * <p>형식이 깨진 값이 오면 <b>업데이트를 요구하지 않는다</b>. 앱이 보낸 값을
     * 못 읽었다고 사용자를 막아버리면, 파싱 버그 하나가 전 사용자 접속 차단이 된다.
     */
    public static boolean isBelow(String current, String minimum) {
        if (current == null || minimum == null) {
            return false;
        }
        try {
            return compare(current, minimum) < 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 음수면 {@code a}가 낮다. */
    public static int compare(String a, String b) {
        String[] left = a.trim().split("\\.");
        String[] right = b.trim().split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = part(left, i);
            int r = part(right, i);
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            // 1.2 와 1.2.0 은 같다 — 없는 마디만 0으로 채운다
            return 0;
        }
        // "1.0.0-beta" 같은 꼬리표는 무시하고 앞쪽 숫자만 읽는다.
        // ★ split을 쓰지 않는다 — "어쩌구"처럼 숫자가 하나도 없으면 split 결과가
        //   빈 배열이라 [0]에서 ArrayIndexOutOfBounds가 난다(NumberFormatException이 아니라
        //   호출부의 catch를 빠져나간다).
        String raw = parts[index].trim();
        int end = 0;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        String value = raw.substring(0, end);
        if (value.isEmpty()) {
            // ★ 숫자가 하나도 없는 마디를 0으로 보면 안 된다.
            //   "어쩌구"가 0.0.0이 되어 "최소 버전 미만"으로 판정되고, 결국
            //   형식이 깨진 값을 보낸 앱이 전부 강제 업데이트 화면에 갇힌다.
            //   호출부(isBelow)가 이 예외를 받아 "판정하지 않음"으로 처리한다.
            throw new NumberFormatException("버전 마디에 숫자가 없다: " + parts[index]);
        }
        return Integer.parseInt(value);
    }
}
