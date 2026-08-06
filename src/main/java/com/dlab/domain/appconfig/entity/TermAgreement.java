package com.dlab.domain.appconfig.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 동의 이력 — <b>append-only</b>.
 *
 * <p>덮어쓰지 않고 쌓는다. 철회도 행으로 남겨야 "언제 동의했다가 언제 철회했나"에
 * 답할 수 있고, 현재 상태는 가장 최근 행이다.
 *
 * <p><b>{@code code}가 아니라 {@link Terms} 행을 가리킨다</b> — 그래야 "그때 그 문구"가
 * 특정된다. code만 저장하면 약관이 개정된 뒤 무엇에 동의했는지 알 수 없다.
 */
@Getter
@Entity
@Table(name = "term_agreement")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermAgreement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terms_id", nullable = false)
    private Terms terms;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    public TermAgreement(Account account, Terms terms, boolean agreed, Instant agreedAt) {
        this.account = account;
        this.terms = terms;
        this.agreed = agreed;
        this.agreedAt = agreedAt;
    }
}
