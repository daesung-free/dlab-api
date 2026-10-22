package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 응시 (v1 6장).
 *
 * <p>★ <b>모든 응시 기록은 삭제하지 않고 누적 저장한다</b>(v1 6장 · 9장).
 */
@Entity
@Table(name = "vocab_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private Short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    private VocabExamRound round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * ★ {@code null} 이면 아직 제출 전이다 — 중간에 앱을 닫은 응시가 남는다.
     * <b>그 건을 지우지 않는다</b>: 지우면 「몇 번 시도했는가」가 사라진다.
     */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** ★ {@code null} 이면 미제출이다 — 0 으로 두면 <b>「다 틀렸다」와 구분되지 않는다.</b> */
    private Short score;

    @Column(name = "total_count", nullable = false)
    private Short totalCount;

    /**
     * 재응시.
     *
     * <p>★ 재응시를 허용하고 전부 저장하며 <b>대표 점수는 최초</b>다(v1 Q5) —
     * 최고점을 대표로 두면 <b>반복 응시로 점수를 올릴 수 있고</b>, 최근으로 두면
     * <b>연습 삼아 다시 푼 것이 기록을 덮는다.</b>
     */
    @Column(nullable = false)
    private boolean retry;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<VocabAnswer> answers = new ArrayList<>();

    public VocabAttempt(Academy academy, Short year, VocabExamRound round,
                        StudentEnrollment enrollment, Instant startedAt,
                        short totalCount, boolean retry) {
        this.academy = academy;
        this.year = year;
        this.round = round;
        this.enrollment = enrollment;
        this.startedAt = startedAt;
        this.totalCount = totalCount;
        this.retry = retry;
    }

    public void addAnswer(VocabAnswer answer) {
        answers.add(answer);
    }

    /** 제출·채점 완료. ★ 서버만 부른다 — 정오 판정이 앱으로 나가지 않는다(v1 4.2). */
    public void submit(Instant at, int correctCount) {
        this.submittedAt = at;
        this.score = (short) correctCount;
    }

    public boolean isSubmitted() {
        return submittedAt != null;
    }
}
