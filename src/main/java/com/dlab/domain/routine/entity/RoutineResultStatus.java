package com.dlab.domain.routine.entity;

/**
 * 루틴 결과 상태 (F-4.11-1).
 *
 * <p><b>오프라인 시험지 기반 흐름이다</b> — 현장 배부 → 학생 가채점 → 교사 검수 → 웹 입력 → 앱 노출.
 * 시트가 이 순서를 명시했다: {@code 예정 → 배부 → 제출 → 검수완료 → 앱노출}, 갈래로 {@code 미제출}·{@code 결시}.
 *
 * <p><b>{@link #REVIEWED}와 {@link #PUBLISHED}를 나눈 이유</b> — 교사가 검수를 끝냈다고
 * 바로 학생에게 보이면 안 된다. 반 전체를 다 채점한 뒤 한 번에 여는 흐름이라,
 * 중간 상태가 학생 앱에 노출되면 "누구는 나왔는데 나는 왜 없냐"가 된다.
 */
public enum RoutineResultStatus {

    /** 세팅만 된 상태. */
    PLANNED,
    /** 시험지 현장 배부됨. */
    DISTRIBUTED,
    /** 학생이 제출함(가채점 포함). */
    SUBMITTED,
    /** 교사 검수 완료 — 아직 학생에게 안 보인다. */
    REVIEWED,
    /** 앱 노출. 학생이 볼 수 있다. */
    PUBLISHED,
    /** 배부됐으나 제출하지 않음. */
    NOT_SUBMITTED,
    /** 결시(응시 자체를 안 함). */
    ABSENT;

    /** 학생 앱에 보이는가. 검수 중인 점수가 새어나가면 안 된다. */
    public boolean isVisibleToStudent() {
        return this == PUBLISHED || this == NOT_SUBMITTED || this == ABSENT;
    }

    /** 점수를 매길 수 있는 상태인가. */
    public boolean isScorable() {
        return this == SUBMITTED || this == REVIEWED || this == PUBLISHED;
    }
}
