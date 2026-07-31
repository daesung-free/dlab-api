package com.dlab.domain.firewall.entity;

/**
 * 승인 주체.
 * PARENT가 기본 승인자이고, 타임아웃 경과 시 STAFF(담당선생님·사감)가 에스컬레이션 승인한다.
 */
public enum ApproverType {
    PARENT,
    STAFF
}
