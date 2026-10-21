package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * D:VOCA 표제어 (개발문서 v2 7장). 50유닛 × 40개 = 2,000개.
 *
 * <h2>★ 열 12개를 전부 저장한다</h2>
 * 발음·유의어·예문 등은 지금 쓰지 않지만 둔다 — <b>2,000행을 다시 넣는 일이 없게</b>(v2 3.4).
 * 결과 화면에 무엇을 보여줄지는 미확정이다(Q9).
 */
@Entity
@Table(name = "vocab_word")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabWord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_no", nullable = false)
    private Short unitNo;

    /**
     * 전역 일련번호.
     *
     * <p>★ <b>문자열이다</b> — {@code '0001'} 처럼 0으로 채워져 있다. 숫자로 읽으면
     * 앞의 0이 날아가고 원고와 대조가 안 된다. 실측으로 {@code 0001}~{@code 2000} 이
     * 빠짐없이 이어지는 것을 확인했다.
     */
    @Column(name = "seq_no", nullable = false, length = 10)
    private String seqNo;

    @Column(nullable = false, length = 100)
    private String word;

    @Column(length = 100)
    private String pronunciation;

    /**
     * 품사.
     *
     * <p>★ <b>복합 품사가 18.7%다</b>({@code v; n}). 단일 값으로 가정하지 말 것 —
     * 객관식 보기 추출은 <b>첫 품사</b>를 대표로 쓴다(v2 6.1).
     *
     * <p>⚠️ 원고에 품사가 빈 단어가 1건 있다({@code beneath}, Unit 15). 추정해 채우지 않는다.
     */
    @Column(length = 20)
    private String pos;

    /**
     * 원고의 뜻 한 칸 그대로.
     *
     * <p>★ <b>이 칸으로 채점하지 말 것.</b> 2,000개 중 1,461개가 여러 뜻을 담고 있어
     * 완전일치로 보면 맞는 답이 오답이 된다 — 정답 후보는 {@link VocabMeaning} 이 든다.
     */
    @Column(name = "meaning_raw", nullable = false, length = 500)
    private String meaningRaw;

    @Column(length = 300)
    private String synonym;

    @Column(length = 300)
    private String antonym;

    @Column(name = "example_en", length = 1000)
    private String exampleEn;

    @Column(name = "example_ko", length = 1000)
    private String exampleKo;

    /** {@code 22 9평}·{@code 24 수능} 형식. 채움률 22% */
    @Column(length = 50)
    private String source;

    @Column(length = 300)
    private String derivative;

    @Column(length = 1000)
    private String etymology;

    @Column(nullable = false)
    private boolean active = true;

    public VocabWord(Short unitNo, String seqNo, String word, String meaningRaw) {
        this.unitNo = unitNo;
        this.seqNo = seqNo;
        this.word = word;
        this.meaningRaw = meaningRaw;
    }

    /** 원고 재업로드 — 같은 번호면 덮어쓴다(중복이 쌓이지 않게, v2 4.4). */
    public void overwrite(Short unitNo, String word, String pronunciation, String pos,
                          String meaningRaw, String synonym, String antonym,
                          String exampleEn, String exampleKo, String source,
                          String derivative, String etymology) {
        this.unitNo = unitNo;
        this.word = word;
        this.pronunciation = pronunciation;
        this.pos = pos;
        this.meaningRaw = meaningRaw;
        this.synonym = synonym;
        this.antonym = antonym;
        this.exampleEn = exampleEn;
        this.exampleKo = exampleKo;
        this.source = source;
        this.derivative = derivative;
        this.etymology = etymology;
    }

    /** 보기 추출용 대표 품사 — {@code v; n} → {@code v} (v2 6.1). */
    public String primaryPos() {
        if (pos == null || pos.isBlank()) {
            return null;
        }
        return pos.split(";")[0].trim();
    }
}
