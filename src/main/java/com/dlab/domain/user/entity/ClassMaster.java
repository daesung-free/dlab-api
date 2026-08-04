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

    /** 담임 = 담당선생님(사감). 미지정일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "homeroom_teacher_id")
    private Teacher homeroomTeacher;

    public ClassMaster(Academy academy, short year, String name, ClassType classType, Teacher homeroomTeacher) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.classType = classType;
        this.homeroomTeacher = homeroomTeacher;
    }

    public void assignHomeroom(Teacher teacher) {
        this.homeroomTeacher = teacher;
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
