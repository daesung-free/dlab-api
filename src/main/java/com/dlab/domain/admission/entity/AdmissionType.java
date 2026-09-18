package com.dlab.domain.admission.entity;

/**
 * 수시 / 정시.
 *
 * <p>★ <b>개수 제한이 다르다</b>(수시 6 · 정시 3). 구분이 없으면 서버가 그 제한을 검증할 수
 * 없고, 통계도 이 축으로 나뉜다.
 */
public enum AdmissionType {
    /** 수시 — 최대 6개 */
    EARLY(6),
    /** 정시 — 최대 3개 */
    REGULAR(3);

    private final int limit;

    AdmissionType(int limit) {
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    public String displayName() {
        return this == EARLY ? "수시" : "정시";
    }
}
