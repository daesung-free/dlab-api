package com.dlab.domain.lecture.entity;

/**
 * 특강 / 설명회.
 *
 * <p>F-4.10-4가 *"특강·설명회 기초 설정"*으로 묶어놨고 둘 다 "열고 → 신청받고 → 명단 관리"라는
 * 같은 흐름이라 한 테이블에 둔다. 나누면 신청·대기자·출석을 두 벌씩 만들게 된다.
 */
public enum LectureType {
    LECTURE,
    BRIEFING
}
