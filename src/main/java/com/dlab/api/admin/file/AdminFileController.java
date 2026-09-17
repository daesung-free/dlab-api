package com.dlab.api.admin.file;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.service.FileAttachmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 웹 — 파일 업로드.
 *
 * <h2>공개·비공개를 경로로 나눈다</h2>
 * 같은 엔드포인트에 플래그로 받으면 <b>화면이 값을 잘못 넣었을 때 성적표가 공개 버킷으로
 * 간다.</b> 되돌리려면 객체를 옮겨야 하는데 그 사이 URL 이 살아 있다. 경로가 다르면
 * 그런 실수가 구조적으로 줄어든다.
 *
 * <p>업로드는 <b>전부 서버를 거친다</b> — 클라이언트가 S3 로 직접 올리면 용량·형식 검증과
 * EXIF 제거를 경로마다 다시 구현해야 하고, 한 곳만 빠뜨리면 검증되지 않은 파일이 들어온다.
 */
@Tag(name = "관리자 · 파일")
@RestController
@RequestMapping("/api/v1/admin/files")
@RequiredArgsConstructor
public class AdminFileController {

    private final FileAttachmentService fileAttachmentService;

    /**
     * 공개 파일 업로드 — 공지 이미지·특강 홍보물처럼 아무나 봐도 되는 것.
     *
     * <p>응답의 {@code url} 은 <b>고정 주소</b>다(CloudFront). 화면이 그대로 저장해 써도 된다.
     *
     * @param ownerType 붙을 대상의 종류({@code NOTICE} 등). 저장 위치와 정리 기준이 된다
     * @param ownerId   대상이 아직 저장되기 전이면 비워 둔다. 저장 후 {@code PATCH} 로 잇는다
     */
    @PostMapping("/public")
    public ApiResponse<FileResponse> uploadPublic(
            @RequestParam @NotBlank String ownerType,
            @RequestParam(required = false) Long ownerId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.success(upload(FileVisibility.PUBLIC, ownerType, ownerId, file));
    }

    /**
     * 비공개 파일 업로드 — 성적표·재학증명서·사유 증빙.
     *
     * <p>응답의 {@code url} 은 <b>짧게 만료된다</b>. 화면이 저장해두고 나중에 열면 403 이
     * 나므로, 열 때마다 {@code GET /{id}/url} 로 새로 받는다.
     */
    @PostMapping("/private")
    public ApiResponse<FileResponse> uploadPrivate(
            @RequestParam @NotBlank String ownerType,
            @RequestParam(required = false) Long ownerId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.success(upload(FileVisibility.PRIVATE, ownerType, ownerId, file));
    }

    /** 대상에 이어 붙인다. 신청서를 저장하기 전에 파일부터 올리는 화면이 쓴다. */
    @PatchMapping("/{attachmentId}/owner")
    public ApiResponse<FileResponse> attach(@PathVariable Long attachmentId,
                                            @RequestParam Long ownerId) {
        return ApiResponse.success(
                FileResponse.of(fileAttachmentService.attach(attachmentId, ownerId), null));
    }

    /** 대상에 붙은 파일 목록. */
    @GetMapping
    public ApiResponse<List<FileResponse>> list(@RequestParam @NotBlank String ownerType,
                                                @RequestParam Long ownerId) {
        return ApiResponse.success(fileAttachmentService.findByOwner(ownerType, ownerId).stream()
                .map(f -> FileResponse.of(f, null))
                .toList());
    }

    /**
     * 볼 수 있는 주소를 받는다.
     *
     * <p>비공개 파일은 열 때마다 불러야 한다 — 발급된 주소는 몇 분 뒤 만료된다.
     */
    @GetMapping("/{attachmentId}/url")
    public ApiResponse<FileUrl> url(@PathVariable Long attachmentId) {
        return ApiResponse.success(new FileUrl(fileAttachmentService.url(attachmentId)));
    }

    /**
     * 첨부를 뗀다.
     *
     * <p><b>S3 의 실제 파일은 남는다</b> — "그때 무엇이 제출됐는가"가 원본 확인·환불 다툼의
     * 근거다. 화면에서만 사라진다.
     */
    @DeleteMapping("/{attachmentId}")
    public ApiResponse<Void> delete(@PathVariable Long attachmentId) {
        fileAttachmentService.delete(attachmentId);
        return ApiResponse.empty();
    }

    private FileResponse upload(FileVisibility visibility, String ownerType, Long ownerId,
                                MultipartFile file) throws IOException {
        FileAttachment saved = fileAttachmentService.upload(visibility, ownerType, ownerId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return FileResponse.of(saved, fileAttachmentService.url(saved.getId()));
    }

    /**
     * @param url 업로드 직후에만 함께 내려준다. 비공개 파일이면 <b>곧 만료된다</b>
     */
    public record FileResponse(Long id, String visibility, String originalName,
                               String contentType, long sizeBytes,
                               String ownerType, Long ownerId, String url) {

        static FileResponse of(FileAttachment f, String url) {
            return new FileResponse(f.getId(), f.getVisibility().name(), f.getOriginalName(),
                    f.getContentType(), f.getSizeBytes(), f.getOwnerType(), f.getOwnerId(), url);
        }
    }

    public record FileUrl(String url) {
    }
}
