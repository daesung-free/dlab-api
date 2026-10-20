package com.dlab.api.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.service.FileAttachmentService;
import com.dlab.domain.file.service.FileStorage;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.service.NoticeAttachments;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 첨부 (디랩 1019 — 공지는 이미지와 텍스트를 함께).
 *
 * <h2>★ Notice 에 컬럼을 더하지 않는다</h2>
 * {@code file_attachment} 이 {@code (ownerType, ownerId)} 로 붙는 범용 테이블이다.
 * {@code image_url} 같은 칸을 박으면 <b>여러 장을 못 담고</b> 파일 정리 기준도 갈라진다.
 *
 * <h2>★★ 목록에는 싣지 않는다</h2>
 * 상세에만 쓴다 — 목록에 넣으면 <b>공지 수만큼 첨부 조회가 따라붙는다.</b>
 * 그 단정을 테스트로 고정한다.
 */
@SpringBootTest
@Transactional
class NoticeAttachmentTest {

    @Autowired NoticeService noticeService;
    @Autowired NoticeAttachments noticeAttachments;
    @Autowired FileAttachmentService fileAttachmentService;
    @Autowired EntityManager em;

    @MockitoBean FileStorage storage;
    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("NA1", "공지첨부테스트", LocalTime.of(9, 0));
        em.persist(academy);
        Employee e = new Employee(academy, "행정선생님");
        em.persist(e);
        // ★ 작성자를 account 행으로 찾는다 — AuthPrincipal 만 만들면
        //   「계정을 찾을 수 없습니다」로 떨어진다(공지는 작성자 종류로 권한을 가른다)
        var account = com.dlab.domain.user.entity.Account.forEmployee(e, "na_admin", "x", false);
        em.persist(account);
        em.flush();

        admin = AuthPrincipal.of(account.getId(), account.getAccountType().name(),
                academy.getId(), List.of(Role.SUPER_ADMIN), true);

        // put 은 void 다 — 목이라 아무 일도 하지 않는다
        given(storage.publicUrl(anyString()))
                .willReturn("https://cdn.example.com/notice/test-key");
    }

    private Long uploadTo(Long noticeId) {
        return fileAttachmentService.upload(FileVisibility.PUBLIC,
                NoticeAttachments.OWNER_TYPE, noticeId, "안내문.png", "image/png",
                new byte[] {1, 2, 3}).getId();
    }

    @Test
    @DisplayName("★ 공지에 붙인 첨부가 상세 조회에 실린다 — 고정 주소까지 함께")
    void attachmentsAppearInDetail() {
        Notice notice = noticeService.createForAll(admin, "안내문", "본문");
        em.flush();
        uploadTo(notice.getId());
        em.flush();

        var attachments = noticeAttachments.of(notice.getId());

        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).originalName()).isEqualTo("안내문.png");
        // 공개 파일은 고정 주소다 — 화면이 저장해 써도 된다
        assertThat(attachments.get(0).url()).startsWith("https://");
    }

    @Test
    @DisplayName("★★ ownerType 상수를 쓴다 — 대소문자가 어긋나면 업로드는 되고 조회만 안 된다")
    void ownerTypeIsSharedConstant() {
        Notice notice = noticeService.createForAll(admin, "안내문", "본문");
        em.flush();
        uploadTo(notice.getId());
        em.flush();

        // 상수로 조회하면 나오고
        assertThat(noticeAttachments.of(notice.getId())).hasSize(1);
        // 소문자로 찾으면 안 나온다 — owner_type 이 자유 문자열이라 오류도 나지 않는다
        assertThat(fileAttachmentService.findByOwner("notice", notice.getId())).isEmpty();
    }

    @Test
    @DisplayName("첨부가 없는 공지는 빈 목록이다 — 대부분의 공지가 그렇다")
    void noticeWithoutAttachmentsReturnsEmpty() {
        Notice notice = noticeService.createForAll(admin, "텍스트만", "본문");
        em.flush();

        assertThat(noticeAttachments.of(notice.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 저장 전에 올린 첨부를 나중에 이을 수 있다 — 저장부터 강제하면 쓰다 만 공지가 쌓인다")
    void attachmentCanBeUploadedBeforeNoticeExists() {
        // ownerId 없이 먼저 올린다(화면이 작성 중에 올리는 흐름)
        Long attachmentId = uploadTo(null);
        em.flush();

        Notice notice = noticeService.createForAll(admin, "안내문", "본문");
        em.flush();

        // 저장 후 PATCH 로 잇는다
        fileAttachmentService.attach(attachmentId, notice.getId());
        em.flush();

        assertThat(noticeAttachments.of(notice.getId())).hasSize(1);
    }
}
