package com.dlab.domain.routine.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 월별 데일리 루틴/테스트 세팅 (F-4.11-1).
 *
 * <p><b>월이 축이다.</b> 시트가 *"월별 세팅 + 전월 복사"*를 요구하는데, 매달 같은 구성을
 * 반복하므로 복사가 기본 흐름이고 그러려면 월 단위여야 한다.
 *
 * <p>▷[0803] 이 결과가 앱 Daily Report의 <b>'데일리테스트' 원천</b>이다(영단어시험 폐기).
 */
@Getter
@Entity
@Table(name = "daily_routine")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRoutine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(nullable = false)
    private short month;

    /** 반별 운영. {@code null}이면 지점 공통 — 반마다 다른 시험지를 쓰는 경우가 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private ClassMaster classMaster;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String subject;

    /** 만점. {@code 0}이면 점수 없이 완료/미완료만 본다. */
    @Column(name = "max_score", nullable = false)
    private short maxScore = 0;

    /** 앱에서 강조 표시된다(A-11 "권장 항목 강조"). */
    @Column(nullable = false)
    private boolean recommended = false;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder = 0;

    /** 전월 복사 원본. {@code null}이면 신규 생성분이다. */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public DailyRoutine(Academy academy, short year, short month, ClassMaster classMaster,
                        String name, String subject, short maxScore, boolean recommended,
                        short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.month = month;
        this.classMaster = classMaster;
        this.name = name;
        this.subject = subject;
        this.maxScore = maxScore;
        this.recommended = recommended;
        this.sortOrder = sortOrder;
    }

    /** 전월 복사본 생성. 원본을 가리켜 두면 "어디서 온 건지"를 나중에 추적할 수 있다. */
    public DailyRoutine copyTo(short targetYear, short targetMonth) {
        DailyRoutine copy = new DailyRoutine(academy, targetYear, targetMonth, classMaster,
                name, subject, maxScore, recommended, sortOrder);
        copy.copiedFromId = this.id;
        return copy;
    }

    /** {@code null}은 "변경하지 않음"이다. */
    public void update(String name, String subject, Short maxScore, Boolean recommended,
                       Short sortOrder) {
        if (name != null) {
            this.name = name;
        }
        if (subject != null) {
            this.subject = subject;
        }
        if (maxScore != null) {
            this.maxScore = maxScore;
        }
        if (recommended != null) {
            this.recommended = recommended;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /** 점수를 매기는 항목인가. 만점이 0이면 완료/미완료만 본다. */
    public boolean isScored() {
        return maxScore > 0;
    }
}
