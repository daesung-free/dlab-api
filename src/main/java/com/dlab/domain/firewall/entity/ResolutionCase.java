package com.dlab.domain.firewall.entity;

/**
 * 승인이 어떤 상황에서 이뤄졌는지. 케이스별로 학부모에게 나가는 안내 문구가 달라야 한다
 * (CLAUDE.md §3 — 특히 아래 2번과 3번을 절대 같은 문구로 통일하지 말 것).
 */
public enum ResolutionCase {
    /** 1. 타임아웃 전에 학부모가 승인 — 정상 흐름 */
    PARENT_IN_TIME,
    /** 2. 학부모 무응답으로 타임아웃이 지난 뒤 담당선생님이 승인 → "승인 시간이 지나 담임이 승인했습니다" */
    STAFF_AFTER_TIMEOUT,
    /** 3. 타임아웃 전인데 담당선생님이 먼저 승인 → "승인 시간이 남았지만 담임이 먼저 승인 처리했습니다" */
    STAFF_BEFORE_TIMEOUT
}
