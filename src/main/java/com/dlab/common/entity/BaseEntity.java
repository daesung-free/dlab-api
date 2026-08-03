package com.dlab.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
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
 * <p>{@code createdBy}는 인증(Security)이 서면 {@code AuditorAware}로 자동 주입한다.
 * 그 전까지는 null이며, 수동으로 채우려면 {@link #recordCreatedBy(Long)}를 쓴다.
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

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    public void recordCreatedBy(Long accountId) {
        this.createdBy = accountId;
    }

    /** soft delete. 물리 삭제를 쓰지 않는다. */
    public void markDeleted() {
        this.deleted = true;
    }
}
