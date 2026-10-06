package com.dlab.integration.zyxel;

/**
 * Zyxel Nebula 와이파이 제어 (F-4.11-10).
 *
 * <h2>★ 제어 단위는 Voucher 코드다 (2026-10-06 벤더 확정, DPPSK 아님)</h2>
 * 플랫폼 업데이트로 <b>Voucher Duration 이 「코드 사용시간 + 인터넷 사용시간」을 함께
 * 제어</b>하게 되어 학생별 사용시간 적용이 가능해졌다 — 원래 DPPSK 를 권했던 이유가
 * 그것이었고, 그 이유가 사라졌다. 그동안 미정이던 E-1 블로커가 이것으로 해소됐다.
 *
 * <h2>운영 모델 — 풀(pool) + Reset 재사용</h2>
 * 지점별로 코드를 미리 만들어 두고, 승인 건이 생기면 <b>미사용 코드 한 장을 배정</b>한다.
 * 매 건 생성·삭제하면 발급 내역이 쌓이고(삭제도 24시간 뒤 자동 제거다) 호출이 늘어난다.
 *
 * <h2>★ 차단은 두 호출이다</h2>
 * {@link #revoke}가 <b>{@code logout-user} 와 {@code delete} 를 모두</b> 보낸다.
 * <ul>
 *   <li>logout 만 하면 — 만료 전 <b>같은 코드로 다시 접속된다</b></li>
 *   <li>delete 만 하면 — <b>이미 붙어 있는 세션이 끊기지 않는다</b>(인증 만료까지 유지)</li>
 * </ul>
 * 그래서 둘 다 해야 "지금 끊기 + 다시 못 들어오기"가 된다.
 *
 * <p><b>도메인은 이 인터페이스만 안다.</b> Nebula API 가 바뀌어도 {@code domain/firewall}은
 * 그대로다 — 연동 코드가 도메인으로 새어나가지 않게 하는 것이 {@code integration} 의 목적이다.
 */
public interface NebulaClient {

    /**
     * 해제 — 미사용 Voucher 한 장을 배정한다.
     *
     * <p>학생에게 전달할 <b>코드를 돌려준다.</b> 이 값을 신청 건에 남겨야 나중에 닫을 수 있다.
     *
     * @param durationMinutes 승인된 사용 시간. 코드 사용시간과 인터넷 시간에 함께 걸린다
     * @return 배정된 Voucher 코드
     */
    String assign(String siteId, int durationMinutes);

    /**
     * 차단 — 현재 세션을 끊고 같은 코드의 재접속을 막는다.
     *
     * <p>만료 스케줄러와 관리자 즉시 회수가 모두 이 경로를 쓴다.
     * <b>실패하면 예외를 던진다</b> — 조용히 넘기면 와이파이가 열린 채로 남는다.
     */
    void revoke(String siteId, String voucherCode);
}
