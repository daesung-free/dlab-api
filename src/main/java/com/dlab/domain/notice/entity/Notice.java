package com.dlab.domain.notice.entity;

import com.dlab.domain.audit.AuditEntityListener;
import com.dlab.domain.audit.Audited;
import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공지 (F-4.11-3).
 *
 * <h2>범위와 대상은 짝이다</h2>
 * {@link NoticeScope}에 맞는 대상만 채워야 한다 — 반공지인데 반이 없으면
 * <b>아무에게도 안 보이고</b>, 전체공지에 반이 붙으면 어느 쪽 기준으로 보여줄지가
 * 코드마다 갈린다. DB CHECK 제약으로도 막아뒀다.
 *
 * <h2>공개 여부는 저장하지 않는다</h2>
 * {@code published_at}·{@code expires_at}과 현재 시각을 비교해 판정한다.
 * 상태 컬럼을 두면 예약 발행 시각이 지나도 <b>배치가 돌기 전까지 안 보인다</b>.
 */
@Getter
@Audited("공지")
@Entity
@Table(name = "notice")
@EntityListeners(AuditEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 전 지점 공지({@link NoticeScope#ALL})면 {@code null}이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoticeScope scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_master_id")
    private ClassMaster classMaster;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private StudentEnrollment enrollment;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 20)
    private NoticeAuthorType authorType;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false)
    private boolean pinned;

    /** 앱 홈 배너 노출(F-4.12-3). */
    @Column(nullable = false)
    private boolean banner;

    /** 예약 발행. {@code null}이면 즉시 공개다. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    private Notice(Academy academy, short year, NoticeScope scope,
                   String title, String content,
                   NoticeAuthorType authorType, Long authorId) {
        this.academy = academy;
        this.year = year;
        this.scope = scope;
        this.title = title;
        this.content = content;
        this.authorType = authorType;
        this.authorId = authorId;
    }

    /** 전 지점 공지. 지점을 갖지 않는다. */
    public static Notice ofAll(short year, String title, String content,
                               NoticeAuthorType authorType, Long authorId) {
        return new Notice(null, year, NoticeScope.ALL, title, content, authorType, authorId);
    }

    public static Notice ofBranch(Academy academy, short year, String title, String content,
                                  NoticeAuthorType authorType, Long authorId) {
        return new Notice(academy, year, NoticeScope.BRANCH, title, content, authorType, authorId);
    }

    public static Notice ofClass(ClassMaster classMaster, short year, String title, String content,
                                 NoticeAuthorType authorType, Long authorId) {
        Notice notice = new Notice(classMaster.getAcademy(), year, NoticeScope.CLASS,
                title, content, authorType, authorId);
        notice.classMaster = classMaster;
        return notice;
    }

    public static Notice ofIndividual(StudentEnrollment enrollment, short year,
                                      String title, String content,
                                      NoticeAuthorType authorType, Long authorId) {
        Notice notice = new Notice(enrollment.getAcademy(), year, NoticeScope.INDIVIDUAL,
                title, content, authorType, authorId);
        notice.enrollment = enrollment;
        return notice;
    }

    /**
     * 내용 수정.
     *
     * <p><b>범위와 대상은 바꾸지 않는다.</b> 반공지를 전체공지로 바꾸면 이미 읽은 사람과
     * 새로 보게 될 사람이 뒤섞이고, 무엇보다 작성 권한 검사를 처음 한 번만 통과하면
     * 범위를 넓힐 수 있게 된다 — 지우고 새로 쓴다.
     */
    public void update(String title, String content, boolean pinned, boolean banner,
                       Instant publishedAt, Instant expiresAt) {
        this.title = title;
        this.content = content;
        this.pinned = pinned;
        this.banner = banner;
        this.publishedAt = publishedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 부분 수정 — <b>{@code null}은 "변경하지 않음"</b>이다.
     *
     * <p>상단 고정 하나를 켜려고 본문까지 함께 보내면, <b>목록이 낡았을 때 남이 고친 본문이
     * 되돌아간다.</b> 고정·배너는 본문과 무관한 조작이라 따로 바꿀 수 있어야 한다.
     *
     * <p>⚠️ 그래서 <b>예약·만료 시각을 이 경로로는 지울 수 없다</b>({@code null}이 "지움"이
     * 아니라 "그대로"다). 지우려면 {@link #schedule}을 타는 전체 수정을 쓴다.
     */
    public void patch(String title, String content, Boolean pinned, Boolean banner,
                      Instant publishedAt, Instant expiresAt) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (pinned != null) {
            this.pinned = pinned;
        }
        if (banner != null) {
            this.banner = banner;
        }
        if (publishedAt != null) {
            this.publishedAt = publishedAt;
        }
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
    }

    public void schedule(Instant publishedAt, Instant expiresAt) {
        this.publishedAt = publishedAt;
        this.expiresAt = expiresAt;
    }

    public void markPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public void markBanner(boolean banner) {
        this.banner = banner;
    }

    /**
     * 지금 앱에 보여야 하는가.
     *
     * <p>{@code publishedAt}이 없으면 즉시 공개다 — 관리자가 대부분 예약 없이 쓰는데
     * 값을 필수로 두면 그때마다 현재 시각을 넣게 된다.
     */
    public boolean isVisibleAt(Instant at) {
        if (publishedAt != null && at.isBefore(publishedAt)) {
            return false;
        }
        return expiresAt == null || at.isBefore(expiresAt);
    }
}
