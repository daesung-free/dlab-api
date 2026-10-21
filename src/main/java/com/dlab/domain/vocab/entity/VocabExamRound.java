package com.dlab.domain.vocab.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공개된 회차 (v1 5.6).
 *
 * <h2>★★ 공개된 것만 저장한다 — 예정 회차는 계산한다</h2>
 * <i>"이미 공개된 회차의 범위는 공개 시점에 확정 저장한다. 나중에 시험 없는 날을
 * 추가해도 <b>지난 회차와 지난 응시 기록이 바뀌지 않아야</b> 한다."</i>
 *
 * <p>예정 일정은 {@link com.dlab.domain.vocab.service.RoundScheduler} 가 계산한다 —
 * 저장하면 시험 없는 날이 추가될 때 <b>이후 일정을 전부 고쳐야</b> 한다.
 */
@Entity
@Table(name = "vocab_exam_round")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabExamRound extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private Short year;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 회독 1~3. ⚠️ 3회독 이후 동작은 미확정이다(v1 Q4) — 지금은 정지한다 */
    @Column(nullable = false)
    private Short cycle;

    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /**
     * 금요일 누적인가.
     *
     * <p>★ 범위는 그 주에 <b>실제로 공개된</b> 유닛 전체다 — 공휴일로 밀리면 그만큼 줄어든다
     * (v1 5.2 예시: 월5·화6·수7·목공휴일 → 금 5-7).
     */
    @Column(nullable = false)
    private boolean weekly;

    @Column(name = "unit_from", nullable = false)
    private Short unitFrom;

    @Column(name = "unit_to", nullable = false)
    private Short unitTo;

    public VocabExamRound(Academy academy, Short year, Integer roundNo, Short cycle,
                          LocalDate openDate, boolean weekly, Short unitFrom, Short unitTo) {
        this.academy = academy;
        this.year = year;
        this.roundNo = roundNo;
        this.cycle = cycle;
        this.openDate = openDate;
        this.weekly = weekly;
        this.unitFrom = unitFrom;
        this.unitTo = unitTo;
    }
}
