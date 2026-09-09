package com.dlab.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 전 테이블 공통 컬럼 (docs/entity-design.md §0-1).
 *
 * <p>{@code createdBy}(감사로그)와 {@code isDeleted}(soft delete)는 나중에 붙이면
 * <b>그 이전 기간의 이력을 영영 복구할 수 없다</b>. 감사로그는 보안심사 직결 항목이라
 * 처음부터 넣는다.
 *
 * <p>{@code createdBy}는 {@code SecurityAuditorAware}가 자동으로 채운다 —
 * 서비스에서 직접 설정하지 말 것. 인증 주체가 없는 경로(배치·스케줄러·DSA 호환 구획)는
 * 시스템 계정({@code 0})으로 기록된다.
 *
 * <p>{@code academyId}·{@code year}는 테이블마다 의미가 달라 여기 두지 않고 각 엔티티가 갖는다
 * (학부모·템플릿처럼 의도적으로 없는 곳도 있다).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 최초 생성자. 저장 시점에 자동 주입되며 이후 바뀌지 않는다({@code updatable = false}).
     * 수정자를 따로 남겨야 하면 {@code @LastModifiedBy}를 추가할 것 —
     * 지금은 스키마에 컬럼이 없다.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /** soft delete. 물리 삭제를 쓰지 않는다. */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * soft delete 되돌리기.
     *
     * <p><b>유니크 제약이 soft delete를 모르는 테이블</b>에서 필요하다 — 예를 들어
     * {@code seat_master}의 {@code UNIQUE (academy_id, seat_cd)}는 부분 인덱스가 아니라서
     * 지운 좌석의 코드가 계속 자리를 차지한다. 그 코드로 다시 등록하려 하면 제약 위반이
     * 나는데, 사용자 입장에서는 "지웠는데 왜 못 만드나"가 된다. 새 행을 만들지 않고
     * 기존 행을 되살린다.
     */
    public void restore() {
        this.deleted = false;
    }
}
