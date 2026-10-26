package com.dlab.api.admin.student;

import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.service.GuardianContactService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자 연락처 관리.
 *
 * <h2>★★ 이 화면이 없으면 학부모 알림톡이 한 통도 못 나간다</h2>
 * 수신처는 보호자 번호이고, 지금까지 그 번호는 <b>학부모가 앱에 가입할 때만</b> 들어왔다.
 * ⚠️ <b>미등원 알림은 끌 수 없는 필수 알림인데 수신처가 없는 상태</b>가 된다.
 * 컷오버 때 기존 재원생 보호자 번호를 넣는 경로도 이것이다.
 *
 * <h2>★ 번호가 같으면 기존 보호자에 연결만 추가한다</h2>
 * 형제자매가 같은 보호자를 공유한다 — 번호에 UNIQUE 가 걸려 있어 새로 만들면 터진다.
 *
 * <h2>⚠️ 승인자 지정은 승인 동작을 바꾸지 않는다</h2>
 * 2026-10-12 회신으로 <b>부·모 양쪽 알림 + 선착순</b>으로 확정됐고, 승인 경로는
 * {@code is_approver} 를 읽지 않는다. 이 값은 DSA {@code getParentHpList} 의 목록 순서용이다.
 * <b>화면에서 「승인자」로 안내하면 안 된다</b> — 지정하지 않은 쪽도 승인할 수 있다.
 */
@Tag(name = "관리자 · 보호자 연락처")
@RestController
@RequestMapping("/api/v1/admin/students/{enrollmentId}/guardians")
@RequiredArgsConstructor
public class AdminGuardianContactController {

    private final GuardianContactService service;

    /** ★ 번호는 열람 권한에 따라 마스킹된다(§7) — 판정은 {@code common/privacy} 한 곳이다. */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<List<GuardianContactView>> list(@CurrentAccount AuthPrincipal me,
                                                        @PathVariable Long enrollmentId) {
        return ApiResponse.success(service.list(me, enrollmentId).stream()
                .map(l -> GuardianContactView.of(l, me)).toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<GuardianContactView> add(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long enrollmentId,
                                                 @Valid @RequestBody AddRequest request) {
        StudentGuardianLink link = service.add(me, enrollmentId, request.name(),
                request.phone(), request.relationOrderOrDefault(), request.approverOrDefault());
        return ApiResponse.success(GuardianContactView.of(link, me));
    }

    /** 연결만 끊는다 — 보호자 행은 다른 자녀가 쓰고 있을 수 있어 지우지 않는다. */
    @DeleteMapping("/{guardianId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<Void> unlink(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId,
                                     @PathVariable Long guardianId) {
        service.unlink(me, enrollmentId, guardianId);
        return ApiResponse.empty();
    }

    @Schema(name = "GuardianContactAddRequest")
    public record AddRequest(
            @NotBlank @Size(max = 20) String name,
            @NotBlank @Size(max = 20) String phone,
            /** 부·모·기타 순서(1~). 비우면 1 */
            @Min(1) @Max(9) Short relationOrder,
            /**
             * ⚠️ 승인 동작을 바꾸지 않는다 — 선착순 승인이라 지정하지 않은 쪽도 승인한다.
             * DSA 목록 순서용이다.
             */
            Boolean approver) {

        short relationOrderOrDefault() {
            return relationOrder == null ? (short) 1 : relationOrder;
        }

        boolean approverOrDefault() {
            return approver != null && approver;
        }
    }

    @Schema(name = "GuardianContactView")
    public record GuardianContactView(Long guardianId, String name, String phone,
                                      short relationOrder, boolean approver, boolean masked) {

        static GuardianContactView of(StudentGuardianLink link, AuthPrincipal me) {
            String phone = PersonalDataPolicy.phone(me, link.getGuardian().getPhone());
            return new GuardianContactView(
                    link.getGuardian().getId(),
                    link.getGuardian().getName(),
                    phone,
                    link.getRelationOrder(),
                    link.isApprover(),
                    // ★ 화면이 마스킹 여부를 알아야 한다 — 모르면 「번호가 잘못 저장됐다」는
                    //   오인 문의가 생긴다(§7)
                    !java.util.Objects.equals(phone, link.getGuardian().getPhone()));
        }
    }
}
