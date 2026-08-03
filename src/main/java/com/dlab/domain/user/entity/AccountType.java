package com.dlab.domain.user.entity;

/** 계정 종류. JWT role claim의 기준이 된다. */
public enum AccountType {
    STUDENT,
    PARENT,
    EMPLOYEE,
    /** 선생님(강사). 직원과 별개 엔티티다(§N). */
    TEACHER
}
