package com.dlab.domain.routine.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 학생별 루틴 결과.
 *
 * <p><b>★ 가채점 점수와 교사 검수 점수를 분리 보관한다</b>(시트 명시).
 * 학생이 스스로 매긴 점수와 교사가 확인한 점수가 다를 수 있고 <b>그 차이 자체가 확인 대상</b>이다 —
 * 한 칸에 덮어쓰면 "학생이 몇 점이라고 했는지"가 사라진다.
 *
 * <p>통계·상벌점의 기준은 <b>교사 검수 점수</b>({@link #reviewedScore})다.
 */
@Getter
@Entity
@Table(name = "daily_routine_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRoutineResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_id", nullable = false)
    private DailyRoutine routine;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "result_date", nullable = false)
    private LocalDate resultDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoutineResultStatus status = RoutineResultStatus.PLANNED;

    /** 학생 가채점. 교사 검수 전 값이다. */
    @Column(name = "self_score")
    private Short selfScore;

    /** 교사 검수 점수. 통계·상벌점의 기준이다. */
    @Column(name = "reviewed_score")
    private Short reviewedScore;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(length = 200)
    private String memo;

    public DailyRoutineResult(DailyRoutine routine, StudentEnrollment enrollment,
                              LocalDate resultDate) {
        this.academy = routine.getAcademy();
        this.year = routine.getYear();
        this.routine = routine;
        this.enrollment = enrollment;
        this.resultDate = resultDate;
    }

    /** 배부. */
    public void distribute() {
        this.status = RoutineResultStatus.DISTRIBUTED;
    }

    /**
     * 제출 + 학생 가채점 기록.
     *
     * <p>가채점은 <b>덮어쓰지 않는다</b> — 이 값은 교사 검수와 대조하는 기준이라
     * 나중에 교사가 점수를 넣어도 그대로 남는다.
     */
    public void submit(Short selfScore) {
        this.status = RoutineResultStatus.SUBMITTED;
        this.selfScore = selfScore;
    }

    /**
     * 교사 검수.
     *
     * <p><b>{@code selfScore}는 건드리지 않는다.</b> 검수 점수만 별도 칸에 넣는다.
     */
    public void review(Short reviewedScore, String memo, Instant at) {
        this.status = RoutineResultStatus.REVIEWED;
        this.reviewedScore = reviewedScore;
        this.memo = memo;
        this.reviewedAt = at;
    }

    /**
     * 앱 노출.
     *
     * <p>검수와 나눈 이유는, 반 전체를 채점한 뒤 <b>한 번에 열어야</b> 하기 때문이다 —
     * 중간에 노출되면 "누구는 나왔는데 나는 왜 없냐"가 된다.
     */
    public void publish() {
        this.status = RoutineResultStatus.PUBLISHED;
    }

    public void markNotSubmitted() {
        this.status = RoutineResultStatus.NOT_SUBMITTED;
    }

    public void markAbsent() {
        this.status = RoutineResultStatus.ABSENT;
    }

    /**
     * 그리드 한 줄을 <b>보낸 그대로</b> 반영한다 — 관리자 일괄 입력용.
     *
     * <p>상태별 메서드는 자기 칸만 건드려서, 그리드에서 가채점을 지우거나 "예정" 으로 되돌려도
     * 조용히 무시되고 저장 건수에는 잡혔다. 그리드는 한 줄이 곧 전체 상태라 두 점수 칸을 모두
     * 보낸 값으로 맞춘다. 제출 전 상태(예정·배부·미제출·결시)에는 점수가 없고, 제출 상태로
     * 되돌리면 검수 점수가 지워진다. <b>예외는 검수 이후의 가채점</b> — 비워 보내면 남긴다.
     *
     * <p>모순되는 입력(제출 전 상태에 점수, 제출 상태에 검수 점수)은 호출부가 먼저 막는다.
     */
    public void overwrite(RoutineResultStatus status, Short selfScore, Short reviewedScore,
                          String memo, Instant at) {
        this.status = status;
        this.memo = memo;
        switch (status) {
            case PLANNED, DISTRIBUTED, NOT_SUBMITTED, ABSENT -> {
                this.selfScore = null;
                this.reviewedScore = null;
                this.reviewedAt = null;
            }
            case SUBMITTED -> {
                this.selfScore = selfScore;
                this.reviewedScore = null;
                this.reviewedAt = null;
            }
            case REVIEWED, PUBLISHED -> {
                // ★ 가채점은 비워 보내면 남긴다 — 학생이 적어낸 값이라 검수 점수와 대조하는 기준이다.
                //   교사가 검수 칸만 채워 보내는 게 보통이라 null 을 "지움" 으로 읽으면 대조가 사라진다
                if (selfScore != null) {
                    this.selfScore = selfScore;
                }
                this.reviewedScore = reviewedScore;
                this.reviewedAt = at;
            }
        }
    }
}
