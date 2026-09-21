package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 반. 담임(담당선생님·사감)을 여기서 지정하고, 학생을 반에 배정하면
 * 승인 에스컬레이션 대상이 자동으로 정해진다.
 * 그래서 "학생별 승인자 사전지정 UI"는 만들지 않는다.
 */
@Getter
@Entity
@Table(name = "class_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_type", nullable = false, length = 10)
    private ClassType classType = ClassType.FIXED;

    /**
     * 정원. <b>NULL이면 정원을 두지 않는 반</b>이다 — 0과 다르다.
     *
     * <p>초과 배정을 DB로 막지 않는다. 마이그레이션 주석대로
     * <b>정원을 넘겨야 하는 예외가 실제로 있어서</b>다(V20260805100000).
     * 화면은 이 값과 현재 인원으로 충원율을 그린다.
     */
    @Column(name = "capacity")
    private Short capacity;

    /** 담임 = 담당선생님(사감). 미지정일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "homeroom_teacher_id")
    private Teacher homeroomTeacher;

    /**
     * 소속 과정. ★ 전년도 복사 시 <b>새 연도 {@code course_type}으로 갈아끼워야 한다</b> —
     * 그냥 복사하면 새 연도 반이 옛 연도 과정을 가리킨다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_type_id")
    private com.dlab.domain.master.entity.CourseType courseType;

    /**
     * 모의고사 반 번호.
     *
     * <p>★ <b>반 이름에서 뽑지 않는다.</b> "고3 1반" 과 "N수 1반" 이 둘 다 1반이 되어
     * 수험번호가 겹친다 — 실제 자료는 지점 안에서 반 번호가 유일하다(1반 {@code 1003~} ·
     * 2반 {@code 2002~}).
     *
     * <p>비어 있으면 그 반 학생은 <b>수험번호를 채번하지 않는다.</b> 틀린 번호를 주는 것보다
     * 없는 편이 낫다 — 번호가 겹치면 남의 성적이 들어간다.
     */
    @Column(name = "exam_class_no")
    private Short examClassNo;

    public void changeExamClassNo(Short examClassNo) {
        this.examClassNo = examClassNo;
    }

    public ClassMaster(Academy academy, short year, String name, ClassType classType, Teacher homeroomTeacher) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.classType = classType;
        this.homeroomTeacher = homeroomTeacher;
    }

    /**
     * 반 기본정보 수정. 넘어온 값만 바꾼다 — 화면이 일부 필드만 보내도 나머지가 지워지지 않는다.
     *
     * <p><b>정원을 비우려면 {@code clearCapacity}를 함께 켠다.</b> null 하나로는
     * "안 보냄"과 "정원 없음"이 구분되지 않는다.
     */
    public void updateDetails(String name, Short capacity, boolean clearCapacity) {
        if (name != null) {
            this.name = name;
        }
        if (clearCapacity) {
            this.capacity = null;
        } else if (capacity != null) {
            this.capacity = capacity;
        }
    }

    /** 전년도 복사가 정원까지 그대로 가져간다. */
    public void changeCapacity(Short capacity) {
        this.capacity = capacity;
    }

    public void assignHomeroom(Teacher teacher) {
        this.homeroomTeacher = teacher;
    }

    public void assignCourseType(com.dlab.domain.master.entity.CourseType courseType) {
        this.courseType = courseType;
    }

    /**
     * 전년도 복사 원본. NULL이면 그 해에 새로 만든 것이다.
     * 복사본과 신규 생성분을 구분할 유일한 근거라 복사 시 반드시 채운다.
     */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }

}
