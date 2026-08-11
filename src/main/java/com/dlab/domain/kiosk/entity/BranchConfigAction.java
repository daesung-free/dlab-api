package com.dlab.domain.kiosk.entity;

/** 지점 설정에서 감사 대상이 되는 변경 (F-4.10-7). */
public enum BranchConfigAction {

    /**
     * 키오스크 자격증명 재발급.
     *
     * <p><b>가장 중요한 항목이다</b> — 재발급하는 순간 그 지점 키오스크가 전부 인증에
     * 실패한다. 장애가 났을 때 "누가 언제 돌렸나"를 못 찾으면 원인 추적이 막힌다.
     */
    KIOSK_CREDENTIAL_ISSUED,

    PG_MERCHANT_CHANGED,

    NEBULA_DEVICE_CHANGED,

    /** 지점별 정책 JSON 변경. */
    POLICY_CHANGED
}
