package com.dlab.domain.schedule.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정기일정 월 단위 제출 (F-4.1-7, 앱 A-7).
 *
 * <p>현강·과외처럼 매주 같은 요일에 나갔다 오는 외부 일정을 미리 등록한다.
 * 승인되면 그 시간의 외출이 무단이 아니게 되어 벌점을 받지 않는다.
 *
 * <h2>승인은 제출 단위다</h2>
 * 월요일 수학·목요일 영어를 다니면 줄은 둘인데 승인은 한 번이다. 줄마다 승인 요청을
 * 만들면 학부모 승인 큐에 같은 학생이 여러 번 뜨고, {@code ApprovalService}가
 * "이미 처리 대기중인 신청이 있습니다"로 두 번째 줄을 막는다.
 *
 * <h2>★ 관리자 등록분은 자동 승인이다</h2>
 * 담임이 학생 대신 넣는 트랙이라(0803 추가) 승인자가 곧 등록자다 — 자기가 넣고 자기가
 * 승인하는 절차를 만들 이유가 없다.
 */
@Getter
@Entity
@Table(name = "regular_schedule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegularSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "schedule_month", nullable = false)
    private short scheduleMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScheduleSource source;

    /** 승인 라우팅에 위임한다. 관리자 등록분은 {@code null}이고 곧바로 승인 상태다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_request_id")
    private ApprovalRequest approvalRequest;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RegularScheduleItem> items = new ArrayList<>();

    public RegularSchedule(StudentEnrollment enrollment, short scheduleMonth,
                           ScheduleSource source) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.scheduleMonth = scheduleMonth;
        this.source = source;
    }

    public void linkApproval(ApprovalRequest approvalRequest) {
        this.approvalRequest = approvalRequest;
    }

    public void addItem(RegularScheduleItem item) {
        items.add(item);
        item.assignTo(this);
    }

    /** 줄을 통째로 갈아끼운다 — 화면이 편집한 전체 목록을 그대로 보낸다. */
    public void replaceItems(List<RegularScheduleItem> newItems) {
        items.clear();
        newItems.forEach(this::addItem);
    }

    /**
     * 승인된 일정인가.
     *
     * <p><b>관리자 등록분은 승인 요청이 없어도 승인된 것이다</b> — 여기서 {@code null}을
     * 미승인으로 보면 담임이 넣은 일정이 영영 인정되지 않는다.
     */
    public boolean isApproved() {
        if (source == ScheduleSource.ADMIN) {
            return true;
        }
        return approvalRequest != null
                && approvalRequest.getStatus() == ApprovalStatus.APPROVED;
    }
}
