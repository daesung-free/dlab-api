package com.dlab.domain.penalty.entity;

/** 점수 부여 경로. 규칙 매핑(I-5) 확정 전까지는 MANUAL만 쓴다. */
public enum PenaltySource {
    KIOSK,
    ROUTINE,
    MANUAL
}
