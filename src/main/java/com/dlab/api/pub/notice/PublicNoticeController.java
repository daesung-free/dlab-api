package com.dlab.api.pub.notice;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.share.service.PublicNoticeShareService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 안내문 — <b>로그인 없이 열린다.</b>
 *
 * <p>「등록비 안내」 카카오 알림톡 버튼이 {@code /notice/:token} 으로 들어오고, 그 화면이
 * 여기를 호출한다. 알림톡에는 로그인한 학부모가 열 수 있는 주소가 필요한데 지금까지
 * 로그인 밖 경로가 도어락 QR 하나뿐이었다.
 *
 * <h2>★★ 없는 토큰·만료 토큰도 HTTP 200 + {@code available:false} 다</h2>
 * 404 로 내리면 <b>토큰을 두드려 보는 쪽에 「이건 있다/없다」 힌트</b>가 된다. 도어락
 * 공개 경로가 같은 이유로 거절도 200({@code provided:false})이므로 그 선례에 맞췄다.
 */
@Tag(name = "공개 · 안내문")
@RestController
@RequestMapping("/api/v1/public/notices")
@RequiredArgsConstructor
public class PublicNoticeController {

    private final PublicNoticeShareService shareService;

    /** 안내문 조회. <b>열 수 없어도 200 이다.</b> */
    @GetMapping("/{token}")
    public ApiResponse<PublicNoticeShareService.View> view(@PathVariable String token) {
        return ApiResponse.success(shareService.view(token));
    }

    /**
     * 첨부 내려받기.
     *
     * <p>⚠️ <b>경로를 새로 뚫은 이유</b> — {@code domain/file} 의 내려받기는 관리자 토큰을
     * 본다. 안내문에 파일이 붙으면 학부모가 열 수 없어, <b>같은 공유 토큰으로 받는 전용
     * 경로</b>를 둔다.
     *
     * <p>★★ <b>그 토큰에 딸린 공지의 첨부만 내려간다.</b> {@code fileId} 만 바꿔 남의 파일을
     * 받을 수 있으면 안 된다 — {@code file_attachment} 는 성적표·재학증명서까지 담는 범용
     * 테이블이라, 소유 검사가 없으면 공개 토큰 하나로 개인 서류가 전부 열린다.
     *
     * <p>★ <b>여기는 {@code ApiResponse} 로 감싸지 않는다.</b> 파일을 여는 경로라 저장소
     * 주소로 302 를 보낸다 — JSON 으로 주소를 내리면 화면이 그걸 꺼내 다시 열어야 하고,
     * 우리 서버가 파일 본문을 중계하면 큰 첨부에서 메모리를 그만큼 쓴다.
     * 공개 첨부는 고정 주소(CloudFront), 비공개는 <b>짧게 만료되는</b> presigned 주소다.
     */
    @GetMapping("/{token}/files/{fileId}")
    public ResponseEntity<Void> download(@PathVariable String token,
                                         @PathVariable Long fileId) {
        return ResponseEntity.status(302)
                .location(URI.create(shareService.fileUrl(token, fileId)))
                .build();
    }
}
