package com.dlab.domain.event.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 연간 행사 (F-4.11-10).
 *
 * <h2>★ 학습계획에 복사하지 않는다</h2>
 * "행사를 학습계획에 반영" 을 학생별 계획 행으로 복사하면, 행사를 고칠 때 <b>이미 복사된
 * 수백 행을 따라다녀야 한다.</b> 하나라도 놓치면 없어진 행사가 그 학생 화면에만 남는다.
 * 조회 시 날짜로 합쳐 내리면 수정·삭제가 그대로 반영된다 — 점검표의 *"행사를 수정·삭제하면
 * 학습계획에서도 함께 정리된다"* 가 **따로 구현하지 않아도 성립한다.**
 *
 * <h2>공휴일과 다르다</h2>
 * 공휴일({@code holiday})은 <b>쉬는 날</b>이라 급식 가능일·교습일수 계산에 쓰이고, 행사는
 * <b>그날 무슨 일이 있다</b>는 표시다. 개교기념일처럼 둘 다인 날은 양쪽에 각각 등록한다 —
 * 합치면 <b>행사를 지웠는데 급식이 열리는</b> 일이 생긴다.
 */
@Getter
@Entity
@Table(name = "annual_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnnualEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    /** {@code null} 이면 전 지점 공통. {@code holiday} 와 같은 규약이다 */
    @Column(name = "academy_id")
    private Long academyId;

    @Column(nullable = false, length = 100)
    private String name;

    /** 하루짜리도 {@code start = end} 로 넣는다 — 기간 행사(수련회 3일 등)가 실재한다 */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private AnnualEventType eventType = AnnualEventType.ACADEMY;

    /** 학습계획·달력에 띄울지. 내부 일정은 등록만 하고 학생에게 안 보일 수 있다 */
    @Column(name = "show_in_plan", nullable = false)
    private boolean showInPlan = true;

    @Column(length = 500)
    private String memo;

    public AnnualEvent(short year, Long academyId, String name,
                       LocalDate startDate, LocalDate endDate,
                       AnnualEventType eventType, boolean showInPlan, String memo) {
        this.year = year;
        this.academyId = academyId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.eventType = eventType == null ? AnnualEventType.ACADEMY : eventType;
        this.showInPlan = showInPlan;
        this.memo = memo;
    }

    public void change(String name, LocalDate startDate, LocalDate endDate,
                       AnnualEventType eventType, Boolean showInPlan, String memo) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (endDate != null) {
            this.endDate = endDate;
        }
        if (eventType != null) {
            this.eventType = eventType;
        }
        if (showInPlan != null) {
            this.showInPlan = showInPlan;
        }
        this.memo = memo;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    /** 전 지점 공통인가. 지점 행사와 함께 조회한다 */
    public boolean isShared() {
        return academyId == null;
    }
}
