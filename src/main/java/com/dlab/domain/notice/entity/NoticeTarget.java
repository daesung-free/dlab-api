package com.dlab.domain.notice.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공지 수신 대상 학생.
 *
 * <h2>★★ 사람마다 공지를 만들면 안 되는 이유</h2>
 * {@link NoticeScope#INDIVIDUAL} 은 <b>학생 한 명</b>이라, 「학생들을 개별 선택하여
 * 발송」(요구사항 1-5)을 담으려면 사람 수만큼 공지를 만들어야 한다. 그러면:
 * <ul>
 *   <li>목록에 <b>같은 글이 N개</b> 떠서 담당자가 「내가 중복으로 보냈나」로 읽는다</li>
 *   <li>오타 하나를 고치려면 <b>N번 고쳐야</b> 하고, 하나라도 빠뜨리면
 *       <b>사람마다 다른 공지</b>를 본 셈이 된다</li>
 *   <li>「읽음 몇 명」 집계가 <b>N개로 쪼개진다</b></li>
 * </ul>
 *
 * <p>그래서 <b>공지 하나에 대상자 목록</b>을 단다 — 설문 {@code SurveyParticipant} 과
 * 같은 모양이다.
 *
 * <h2>⚠️ `INDIVIDUAL` 을 없애지 않는다</h2>
 * 이미 그렇게 보낸 공지가 있을 수 있다. 새 범위 {@link NoticeScope#SELECTED} 를 더해
 * <b>대상자 테이블을 쓰는 쪽</b>을 가린다.
 */
@Getter
@Entity
@Table(name = "notice_target")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeTarget extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "year", nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    public NoticeTarget(Notice notice, StudentEnrollment enrollment) {
        this.notice = notice;
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
    }
}
