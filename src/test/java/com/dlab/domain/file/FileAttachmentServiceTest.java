package com.dlab.domain.file;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.service.FileAttachmentService;
import com.dlab.domain.file.service.FileStorage;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 첨부파일.
 *
 * <p>지키려는 것 — <b>허용 목록 밖은 막을 것</b>, <b>키에 원래 파일명이 들어가지 않을 것</b>,
 * <b>뗀 파일의 S3 객체는 남을 것</b>.
 *
 * <p>실제 S3 는 부르지 않는다. 자격증명이 없는 CI 에서 돌아야 하고, 여기서 검증하려는 것은
 * 저장소가 아니라 <b>그 앞에서 거르는 규칙</b>이다.
 */
@SpringBootTest
@Transactional
class FileAttachmentServiceTest {

    @Autowired
    FileAttachmentService service;

    @MockitoBean
    FileStorage storage;

    private final List<String> putKeys = new ArrayList<>();

    private FileAttachment upload(String name, String contentType, byte[] body) {
        doAnswer(inv -> {
            putKeys.add(inv.getArgument(1));
            return null;
        }).when(storage).put(any(), anyString(), any(InputStream.class), anyLong(), anyString());
        return service.upload(FileVisibility.PRIVATE, "ADMISSION_CONSULT", 7L,
                name, contentType, body);
    }

    @Test
    @DisplayName("★ 허용 목록 밖 형식은 막는다 — 금지 목록은 빠뜨린 확장자가 곧 구멍이다")
    void rejectsDisallowedType() {
        assertThatThrownBy(() -> upload("악성.exe", "application/x-msdownload", new byte[]{1, 2}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("올릴 수 있습니다");
    }

    @Test
    @DisplayName("빈 파일은 막는다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> upload("빈.pdf", "application/pdf", new byte[0]))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 객체 키에 원래 파일명이 들어가지 않는다 — 이름 자체가 개인정보인 경우가 있다")
    void keyDoesNotLeakFileName() {
        FileAttachment saved = upload("홍길동_진단서.pdf", "application/pdf", new byte[]{37, 80});

        assertThat(putKeys).hasSize(1);
        String key = putKeys.get(0);
        assertThat(key).doesNotContain("홍길동");
        // {도메인}/{연도}/{대상id}/{uuid}.pdf
        assertThat(key).matches("admission-consult/\\d{4}/7/[0-9a-f-]{36}\\.pdf");

        // 원래 이름은 내려받을 때 쓰라고 메타에만 남는다
        assertThat(saved.getOriginalName()).isEqualTo("홍길동_진단서.pdf");
    }

    @Test
    @DisplayName("★ 첨부를 떼도 S3 객체는 지우지 않는다 — 무엇이 제출됐는지가 근거로 남아야 한다")
    void deleteKeepsObject() {
        FileAttachment saved = upload("성적표.pdf", "application/pdf", new byte[]{37, 80});

        service.delete(saved.getId());

        assertThat(service.findByOwner("ADMISSION_CONSULT", 7L)).isEmpty();
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .delete(any(), eq(saved.getObjectKey()));
    }

    @Test
    @DisplayName("비공개 파일 주소는 presigned 로 나간다 — 공개 주소를 쓰면 403 이다")
    void privateUsesPresigned() {
        FileAttachment saved = upload("증빙.pdf", "application/pdf", new byte[]{37, 80});
        org.mockito.Mockito.when(storage.presignedGetUrl(anyString(), any(Duration.class)))
                .thenReturn("https://s3.example/signed");

        assertThat(service.url(saved.getId())).isEqualTo("https://s3.example/signed");
    }
}
