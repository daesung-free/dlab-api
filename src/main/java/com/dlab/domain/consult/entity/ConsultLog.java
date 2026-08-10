package com.dlab.domain.consult.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상담 일지 (F-4.11-4).
 *
 * <p>DSA에 대응 화면이 없다 — <b>구글시트로 운영하던 것을 시스템화</b>하는 것이다.
 *
 * <h2>리포트는 여기 없다</h2>
 * 학부모용 요약(과목별 이행률 별점 · 성적 상세 결합 · 담임 스티커)은 성적(F-4.6-1) ·
 * 학습계획(F-4.11-2) · 신상기록부(F-4.11-8)에 얹혀 있어 그쪽이 생겨야 만든다.
 */
@Getter
@Entity
@Table(name = "consult_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultLog extends BaseEntity {

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

    /** 상담한 사람 — 담당선생님이다. 행정({@code employee})은 상담을 하지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Enumerated(EnumType.STRING)
    @Column(name = "consult_type", nullable = false, length = 20)
    private ConsultType consultType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsultMethod method = ConsultMethod.FACE;

    @Column(name = "consulted_at", nullable = false)
    private LocalDate consultedAt;

    /** "20분 · 상담실 2" 같은 자유 입력(화면 그대로). */
    @Column(name = "place_note", length = 100)
    private String placeNote;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 학생과 합의한 실행 계획. 다음 상담에서 이행을 확인한다. */
    @Column(name = "action_plan", columnDefinition = "text")
    private String actionPlan;

    @Column(name = "action_done", nullable = false)
    private boolean actionDone;

    /** 다음 상담 예정일. 현황 화면이 "D-1 예정 / 지연 3일"을 이걸로 센다. */
    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConsultLogTag> tags = new ArrayList<>();

    public ConsultLog(StudentEnrollment enrollment, Teacher teacher, ConsultType consultType,
                      ConsultMethod method, LocalDate consultedAt, String content) {
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.enrollment = enrollment;
        this.teacher = teacher;
        this.consultType = consultType;
        this.method = method;
        this.consultedAt = consultedAt;
        this.content = content;
    }

    public void update(ConsultType consultType, ConsultMethod method, LocalDate consultedAt,
                       String placeNote, String content, String actionPlan,
                       boolean actionDone, LocalDate nextDueDate) {
        this.consultType = consultType;
        this.method = method;
        this.consultedAt = consultedAt;
        this.placeNote = placeNote;
        this.content = content;
        this.actionPlan = actionPlan;
        this.actionDone = actionDone;
        this.nextDueDate = nextDueDate;
    }

    /**
     * 태그를 화면이 보낸 목록으로 맞춘다.
     *
     * <p><b>전부 지우고 다시 넣지 않는다.</b> 그러면 같은 (일지, 태그) 쌍이 한 flush 안에서
     * 삭제·삽입 둘 다 생기는데, Hibernate가 삽입을 먼저 내보내 유니크 제약에 걸린다.
     * <b>빠진 것만 지우고 새 것만 더한다.</b>
     */
    public void replaceTags(List<ConsultTag> newTags) {
        java.util.Set<Long> targetIds = newTags.stream()
                .map(ConsultTag::getId)
                .collect(java.util.stream.Collectors.toSet());

        tags.removeIf(link -> !targetIds.contains(link.getTag().getId()));

        java.util.Set<Long> keptIds = tags.stream()
                .map(link -> link.getTag().getId())
                .collect(java.util.stream.Collectors.toSet());
        newTags.stream()
                .filter(t -> !keptIds.contains(t.getId()))
                .forEach(t -> tags.add(new ConsultLogTag(this, t)));
    }

    public List<ConsultTag> tagList() {
        return tags.stream().map(ConsultLogTag::getTag).toList();
    }
}
