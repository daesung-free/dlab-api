package com.dlab.domain.user.entity;

/** 재원 상태. 가입 승인 상태({@link AccountStatus})와 별개 축이다. */
public enum EnrollmentStatus {
    ENROLLED,
    ON_LEAVE,
    WITHDRAWN,
    GRADUATED
}
