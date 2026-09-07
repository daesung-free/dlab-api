package com.dlab.domain.notice.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공지 열람 기록.
 *
 * <p><b>최초 열람 시각만 남긴다.</b> 다시 볼 때마다 갱신하면 "언제 처음 봤나"가 사라지고,
 * 재열람 횟수는 이 화면이 묻는 값이 아니다.
 *
 * <p><b>학생 기준이다.</b> 학부모가 자녀 화면에서 본 것은 학생이 읽은 것이 아니라,
 * 같은 행으로 세면 "학생이 공지를 봤다"는 판단이 흐려진다.
 */
@Getter
@Entity
@Table(name = "notice_read")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeRead extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    public NoticeRead(Notice notice, StudentEnrollment enrollment, Instant readAt) {
        this.notice = notice;
        this.enrollment = enrollment;
        this.readAt = readAt;
    }
}
