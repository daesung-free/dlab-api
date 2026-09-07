package com.dlab.domain.audit;

/**
 * 감사 로그의 동작 구분.
 *
 * <p><b>soft delete 는 {@link #DELETE}다.</b> {@code is_deleted}만 바뀐 UPDATE 로 남기면
 * 화면에서 삭제를 구분할 수 없다 — 이 화면이 가장 자주 찾는 것이 "누가 지웠나"다.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE
}
