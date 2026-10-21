package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Part 3 — 구동사·숙어·혼동어휘 300개 (개발문서 v2 3.1).
 *
 * <p>⚠️ <b>시험 범위에 넣을지는 미확정이다</b>(Q8). 데이터만 적재해 둔다 —
 * 나중에 범위에 들어오면 300행을 다시 넣지 않아도 된다.
 */
@Entity
@Table(name = "vocab_phrase")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabPhrase extends BaseEntity {

    /**
     * 종류. ★ <b>유닛 구간마다 다르다</b>(실측 확인).
     *
     * <pre>
     * Unit 01~20   구동사     PHRASAL_VERB
     * Unit 21~30   혼동 어휘   CONFUSING
     * Unit 31~50   숙어       IDIOM
     * </pre>
     *
     * <p>★ <b>첫 열 이름으로 판별한다.</b> 유닛 번호로 추정하지 않는다 — 원고가 개정되면
     * 구간이 바뀔 수 있고, 그때 조용히 틀린 종류로 들어간다.
     */
    public enum Kind {
        PHRASAL_VERB("구동사"),
        CONFUSING("혼동 어휘"),
        IDIOM("숙어");

        private final String header;

        Kind(String header) {
            this.header = header;
        }

        public String header() {
            return header;
        }

        /** 첫 열 이름으로 찾는다. 공백 차이를 무시한다 */
        public static Kind ofHeader(String header) {
            if (header == null) {
                return null;
            }
            String normalized = header.replaceAll("\\s+", "");
            for (Kind kind : values()) {
                if (kind.header.replaceAll("\\s+", "").equals(normalized)) {
                    return kind;
                }
            }
            return null;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_no", nullable = false)
    private Short unitNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, length = 200)
    private String expression;

    @Column(length = 500)
    private String meaning;

    @Column(name = "example_en", length = 1000)
    private String exampleEn;

    /**
     * 예문 해석.
     *
     * <p>⚠️ <b>줄바꿈이 들어 있다</b>(300건 중 25건 — 뜻이 ①② 두 개인 경우).
     * 그대로 보존한다 — 한 줄로 합치면 두 뜻이 붙어 읽을 수 없다.
     */
    @Column(name = "example_ko", length = 1000)
    private String exampleKo;

    public VocabPhrase(Short unitNo, Kind kind, String expression, String meaning,
                       String exampleEn, String exampleKo) {
        this.unitNo = unitNo;
        this.kind = kind;
        this.expression = expression;
        this.meaning = meaning;
        this.exampleEn = exampleEn;
        this.exampleKo = exampleKo;
    }

    public void overwrite(Kind kind, String meaning, String exampleEn, String exampleKo) {
        this.kind = kind;
        this.meaning = meaning;
        this.exampleEn = exampleEn;
        this.exampleKo = exampleKo;
    }
}
