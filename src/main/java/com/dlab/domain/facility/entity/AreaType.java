package com.dlab.domain.facility.entity;

/**
 * 구역 종류.
 *
 * <p>좌석 테이블은 하나다 — 반 좌석표를 따로 만들면 생성·격자·배치도·사용중지를 한 벌
 * 더 짜게 된다. 대신 <b>종류로 갈라서</b> 목록과 노출 범위를 나눈다.
 */
public enum AreaType {

    /**
     * 독서실. <b>키오스크가 이 구역만 조회한다</b>(docs/dsa-compat.md 3.7·3.8) —
     * 교실이 섞여 내려가면 단말 좌석 선택 화면에 반이 뜬다.
     */
    STUDY,

    /** 반 교실. 반({@code class_master})에 1:1로 붙고 관리자 웹에서만 쓴다. */
    CLASSROOM
}
