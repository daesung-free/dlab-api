package com.dlab.integration.zyxel;

/**
 * Zyxel Nebula 방화벽 제어 (F-4.11-10).
 *
 * <h2>★ 인터페이스만 확정하고 구현은 나중에 끼운다</h2>
 * <b>제어 단위가 미확정이다</b>(E-1) — 학생 단말(MAC)을 여는 것인지, 정책·그룹을 바꾸는
 * 것인지 정해지지 않았다. 그래서 대상을 {@code target} 문자열 하나로 받는다.
 * 확정되면 이 인터페이스의 구현체만 갈아끼우고 도메인은 건드리지 않는다.
 *
 * <p><b>도메인은 이 인터페이스만 안다.</b> Nebula API가 바뀌어도 {@code domain/firewall}은
 * 그대로다 — 연동 코드가 도메인으로 새어나가지 않게 하는 것이 {@code integration} 패키지의 목적이다.
 */
public interface NebulaClient {

    /**
     * 해제.
     *
     * @param target 제어 대상. 단말 MAC인지 정책 ID인지는 E-1 확정 후 정해진다
     */
    void allow(String siteId, String target);

    /** 차단. 만료 스케줄러가 호출한다. */
    void block(String siteId, String target);
}
