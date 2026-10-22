package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정답 후보 (개발문서 v2 5.2).
 *
 * <h2>★★ 왜 원고 뜻으로 직접 채점하지 않는가</h2>
 * 원고 2,000개 중 <b>1,461개(73%)가 한 칸에 여러 뜻을 담고 있다.</b>
 * {@code financial = 재정의, 금융의} 에 학생이 「금융의」라고 쓰면 <b>맞는 답인데
 * 오답이 된다.</b>
 */
@Entity
@Table(name = "vocab_meaning")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabMeaning extends BaseEntity {

    /** 어디서 온 후보인가. */
    public enum Origin {
        /** 원고에서 규칙으로 쪼갠 것. ★ 재업로드 때 <b>다시 만든다</b> */
        SPLIT,
        /** 관리자가 오답 화면에서 넣은 인정 표현. ★ 재업로드 때 <b>남긴다</b> */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id", nullable = false)
    private VocabWord word;

    @Column(nullable = false, length = 300)
    private String text;

    /**
     * ★★ 재업로드 때 {@code SPLIT} 만 다시 만들고 {@code MANUAL} 은 남긴다.
     *
     * <p>구분하지 않으면 원고를 다시 올릴 때 <b>관리자가 쌓아 둔 인정 표현이 통째로
     * 사라진다.</b> 그건 학생들의 실제 오답을 보고 하나씩 넣은 것이라 복구할 방법이 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Origin origin;

    public VocabMeaning(VocabWord word, String text, Origin origin) {
        this.word = word;
        this.text = text;
        this.origin = origin;
    }
}
