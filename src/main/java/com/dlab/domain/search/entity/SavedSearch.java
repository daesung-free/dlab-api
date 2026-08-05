package com.dlab.domain.search.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 저장된 검색조건 (실행가이드 P1-01 "조건저장").
 *
 * <p>학생 검색이 12개 조건이라 자주 쓰는 조합을 매번 다시 입력하는 게 부담이다.
 * "내 반 재원생" 같은 조합을 이름 붙여 저장해두고 불러 쓴다.
 *
 * <p><b>조건을 컬럼으로 펼치지 않는다.</b> 조건이 화면마다 다르고 더 늘 수 있는데 펼치면
 * 조건 하나 추가할 때마다 마이그레이션이 필요해진다. 게다가 이 값으로 검색하는 게 아니라
 * <b>화면에 그대로 되돌려주는</b> 용도라 서버가 파싱할 일이 없다.
 */
@Getter
@Entity
@Table(name = "saved_search")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedSearch extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /** 저장한 사람. 개인 설정이라 계정 단위다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "search_type", nullable = false, length = 30)
    private SearchType searchType;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String conditions;

    public SavedSearch(Academy academy, Account account, SearchType searchType,
                       String name, String conditions) {
        this.academy = academy;
        this.account = account;
        this.searchType = searchType;
        this.name = name;
        this.conditions = conditions;
    }

    public void update(String name, String conditions) {
        if (name != null) {
            this.name = name;
        }
        if (conditions != null) {
            this.conditions = conditions;
        }
    }

    public boolean ownedBy(Long accountId) {
        return account.getId().equals(accountId);
    }
}
