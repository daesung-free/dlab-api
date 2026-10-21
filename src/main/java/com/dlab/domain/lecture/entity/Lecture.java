package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.common.web.Patch;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.time.LocalDate;

/**
 * 특강·설명회 마스터 (F-4.10-4 기초 설정).
 *
 * <p>결제는 붙어 있지 않다 — 0803 답변서가 *"특강 신청+결제 가능 여부 검토 중"*이고
 * {@code payment} 도메인 자체가 없다. {@link #fee}는 <b>안내용</b>이다.
 */
@Getter
@Entity
@Table(name = "lecture")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lecture extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "lecture_type", nullable = false, length = 20)
    private LectureType lectureType = LectureType.LECTURE;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 앱 노출 여부 (0803 "개설 시에만 노출").
     * <b>{@link #status}와 다른 축</b>이다 — 접수를 닫아도 목록에는 보일 수 있다.
     */
    @Column(nullable = false)
    private boolean visible = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureStatus status = LectureStatus.DRAFT;

    /** {@code null}이면 무제한. 설명회는 정원을 안 두는 경우가 있다. */
    private Integer capacity;

    @Column(name = "apply_from")
    private Instant applyFrom;

    @Column(name = "apply_to")
    private Instant applyTo;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** ⚠️ 안내용. 이 값으로 수납이 일어나지 않는다. */
    @Column(nullable = false)
    private int fee = 0;

    /** 선택. 이름이 바뀌어도 유지되는 키다 — 지점·연도 안에서 유일하다. */
    @Column(length = 30)
    private String code;

    /**
     * 유형 세분류(단과·실전·해설).
     *
     * <p><b>비어 있을 수 있다</b> — 유형이 하나도 등록되지 않은 상태에서도 특강 등록이
     * 되어야 하고, 설명회는 애초에 세분류가 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private LectureCategory category;

    public void changeCategory(LectureCategory category) {
        this.category = category;
    }

    /**
     * 담당 강사.
     *
     * <p>⚠️ {@code teacher}는 <b>담당선생님(사감)만</b> 들어가는 테이블이다. 외부 강사를
     * 세우는 특강이 있으면 여기로는 담을 수 없다 — 그때 확장한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private com.dlab.domain.user.entity.Teacher teacher;

    /**
     * 대기 인원 상한 (디랩 1021 답변 Q3).
     *
     * <p>디랩 원문: <i>"(가) 로 준비하되 <b>대기인원으로 유입될 수 있도록</b> 하는 것이
     * 좋을 것 같습니다."</i>
     *
     * <p>{@code null} 이면 무제한 — {@link #capacity} 와 같은 규약이다.
     * 대기 전환 자체는 이미 돌고 있었고(정원이 차면 {@code WAITLISTED}), 없던 것이 상한이다.
     */
    @Column(name = "waitlist_capacity")
    private Integer waitlistCapacity;

    /**
     * 수강 대상 계열 (디랩 1021 답변 Q2 — 「병행」 확정).
     *
     * <p>디랩 원문: <i>"계열과 학년을 <b>둘 다</b> 고를 수 있게 부탁드립니다.
     * 논술 특강 같은 경우는 인문논술, 자연논술로 나뉘기 때문에 학년/계열이 모두 필요합니다."</i>
     *
     * <h2>★★ 비어 있으면 제한 없음이다 — 「전체」를 행으로 만들지 말 것</h2>
     * 「전체」행과 「인문+자연 둘 다 체크」가 <b>같은 뜻인데 데이터가 두 가지로 갈린다.</b>
     * 그러면 화면마다 어느 쪽을 쓰는지 달라지고, 나중에 둘을 합칠 수 없다.
     */
    @ElementCollection(targetClass = TrackType.class, fetch = FetchType.LAZY)
    @CollectionTable(name = "lecture_target_track",
            joinColumns = @JoinColumn(name = "lecture_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "track", length = 20)
    // ⚠️ QueryDSL 이 Set<Enum> 경로를 NumberPath<<any>> 로 잘못 만들어 Q클래스가 컴파일되지
    //    않는다. 쿼리로 걸 일이 없는 값 집합이라 생성에서 뺀다
    @com.querydsl.core.annotations.QueryType(com.querydsl.core.annotations.PropertyType.NONE)
    private Set<TrackType> targetTracks = new LinkedHashSet<>();

    /**
     * 수강 대상 학년. 비어 있으면 제한 없음(위와 같은 규약).
     *
     * <p>⚠️ <b>{@code STAFF} 는 넣을 수 없다</b> — 직원은 학년이 아니라 같은 등록 테이블을
     * 쓰기 위한 구분값이다({@link GradeType}). 대상에 넣으면 직원이 특강 신청 대상이 된다.
     *
     * <p>⚠️ <b>고1({@code HIGH1}) 이 없다.</b> 디랩 답변은 「N수생/고3/고2/고1」인데 우리
     * {@code GradeType} 은 3분류다(고2·고3·n수생). 고1 특강을 실제로 받는다면 enum 추가가
     * 필요하고, 그건 가입·성적 양식까지 번진다 — 확인이 필요하다.
     */
    @ElementCollection(targetClass = GradeType.class, fetch = FetchType.LAZY)
    @CollectionTable(name = "lecture_target_grade",
            joinColumns = @JoinColumn(name = "lecture_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 20)
    // ⚠️ QueryDSL 이 Set<Enum> 경로를 NumberPath<<any>> 로 잘못 만들어 Q클래스가 컴파일되지
    //    않는다. 쿼리로 걸 일이 없는 값 집합이라 생성에서 뺀다
    @com.querydsl.core.annotations.QueryType(com.querydsl.core.annotations.PropertyType.NONE)
    private Set<GradeType> targetGrades = new LinkedHashSet<>();

    /** 대기 상한을 넘었는가. {@code null} 상한은 무제한이다. */
    public boolean waitlistFull(long waitlisted) {
        return waitlistCapacity != null && waitlisted >= waitlistCapacity;
    }

    /**
     * 이 학생이 신청할 수 있는가.
     *
     * <p>★ <b>표시만 하면 고3 대상 특강에 고1이 신청한다.</b> 실제로 검사한다.
     * 두 축은 <b>AND</b> 다 — 「인문계열 고3」 특강이면 둘 다 맞아야 한다.
     */
    public boolean eligible(TrackType track, GradeType grade) {
        if (!targetTracks.isEmpty() && (track == null || !targetTracks.contains(track))) {
            return false;
        }
        return targetGrades.isEmpty() || (grade != null && targetGrades.contains(grade));
    }

    /** 대상을 통째로 바꾼다 — 부분 수정을 두면 「비움」과 「안 보냄」이 구분되지 않는다. */
    public void changeTargets(Set<TrackType> tracks, Set<GradeType> grades) {
        if (tracks != null) {
            this.targetTracks.clear();
            this.targetTracks.addAll(tracks);
        }
        if (grades != null) {
            this.targetGrades.clear();
            // ⚠️ 직원은 대상이 아니다
            grades.stream().filter(g -> !g.isStaff()).forEach(this.targetGrades::add);
        }
    }

    public void changeWaitlistCapacity(Integer waitlistCapacity) {
        this.waitlistCapacity = waitlistCapacity;
    }

    public void changeTeacher(com.dlab.domain.user.entity.Teacher teacher) {
        this.teacher = teacher;
    }

    public Lecture(Academy academy, short year, LectureType lectureType, String name) {
        this.academy = academy;
        this.year = year;
        this.lectureType = lectureType == null ? LectureType.LECTURE : lectureType;
        this.name = name;
    }

    /** {@code null}은 "변경하지 않음"이다. */
    /**
     * 수정.
     *
     * <p><b>이름·코드는 지울 수 없다</b>({@code null}이면 그대로 둔다) — 없으면 목록에 빈
     * 줄이 생기고 신청 화면이 무엇을 신청하는지 알 수 없다.
     *
     * <p>나머지는 {@link Patch} 라 <b>비우는 요청과 안 보낸 요청이 구분된다.</b> 비우면
     * 정원은 제한 없음, 신청기간은 제한 없음이 된다.
     *
     * <p>{@code fee} 는 {@code int} 라 비우면 0(무료)이다 — 금액은 "없음"과 "0원"을 나눌
     * 이유가 없다.
     */
    public void update(String name, String code, Patch<String> description,
                       Patch<Integer> capacity, Patch<Instant> applyFrom, Patch<Instant> applyTo,
                       Patch<LocalDate> startDate, Patch<LocalDate> endDate, Patch<Integer> fee) {
        if (name != null) {
            this.name = name;
        }
        if (code != null) {
            this.code = code;
        }
        if (description != null) {
            this.description = description.value();
        }
        if (capacity != null) {
            this.capacity = capacity.value();
        }
        if (applyFrom != null) {
            this.applyFrom = applyFrom.value();
        }
        if (applyTo != null) {
            this.applyTo = applyTo.value();
        }
        if (startDate != null) {
            this.startDate = startDate.value();
        }
        if (endDate != null) {
            this.endDate = endDate.value();
        }
        if (fee != null) {
            this.fee = fee.isCleared() ? 0 : fee.value();
        }
    }

    public void changeStatus(LectureStatus status) {
        this.status = status;
    }

    public void changeVisible(boolean visible) {
        this.visible = visible;
    }

    /** 정원 없음 = 무제한. */
    public boolean hasCapacity() {
        return capacity != null;
    }

    /**
     * 지금 신청을 받는가.
     *
     * <p>세 가지를 <b>모두</b> 본다 — 상태가 {@code OPEN}이고, 접수 기간 안이고, 삭제되지 않았을 것.
     * 접수 기간을 안 보면 마감된 특강에 계속 신청이 들어온다.
     */
    public boolean acceptsApplicationAt(Instant now) {
        if (isDeleted() || !status.acceptsApplication()) {
            return false;
        }
        if (applyFrom != null && now.isBefore(applyFrom)) {
            return false;
        }
        return applyTo == null || !now.isAfter(applyTo);
    }
}
