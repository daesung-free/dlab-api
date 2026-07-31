package com.dlab.domain.firewall.entity;

public enum FirewallStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** 학생이 직접 취소 */
    CANCELED
}
