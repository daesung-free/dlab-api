package com.dlab.api.admin.master;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.master.repository.DormAssignmentRepository;
import com.dlab.domain.master.service.DormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 웹 — 기숙사 방 관리·배정 (배정관리).
 *
 * <p>사물함이 {@code /masters/lockers}인 것과 나란히 {@code /masters/dorms}에 둔다.
 * 다만 <b>배정 구조가 다르다</b> — 사물함은 1칸 1명이라 방에 학생을 붙이지만,
 * 기숙사는 한 방에 여러 명이라 배정이 별도 테이블이다.
 */
@RestController
@RequestMapping("/api/v1/admin/masters/dorms")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminDormController {

    private final DormService dormService;
    private final DormAssignmentRepository assignmentRepository;

    @GetMapping
    public ApiResponse<List<MasterResponses.DormRoom>> rooms(@CurrentAccount AuthPrincipal me,
                                                             @RequestParam Long academyId,
                                                             @RequestParam Integer year) {
        return ApiResponse.success(dormService.rooms(academyId, year.shortValue(), me).stream()
                .map(room -> MasterResponses.DormRoom.from(
                        room, assignmentRepository.countActiveByRoomId(room.getId())))
                .toList());
    }

    @GetMapping("/{roomId}/occupants")
    public ApiResponse<List<MasterResponses.DormOccupant>> occupants(@CurrentAccount AuthPrincipal me,
                                                                     @PathVariable Long roomId) {
        return ApiResponse.success(dormService.occupants(roomId, me).stream()
                .map(MasterResponses.DormOccupant::from).toList());
    }

    @PostMapping
    public ApiResponse<MasterResponses.DormRoom> createRoom(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody MasterRequests.CreateDormRoom request) {
        var room = dormService.createRoom(request.academyId(), request.year().shortValue(),
                request.building(), request.roomNo(), request.capacity().shortValue(),
                request.gender(), me);
        return ApiResponse.success(MasterResponses.DormRoom.from(room, 0));
    }

    /** 정원은 현재 인원보다 작게 줄일 수 없다 — 누가 나가야 하는지 시스템이 정할 수 없다. */
    @PatchMapping("/{roomId}")
    public ApiResponse<MasterResponses.DormRoom> updateRoom(
            @CurrentAccount AuthPrincipal me, @PathVariable Long roomId,
            @Valid @RequestBody MasterRequests.UpdateDormRoom request) {
        var room = dormService.updateRoom(roomId, request.building(), request.roomNo(),
                request.capacity() == null ? null : request.capacity().shortValue(),
                request.gender(), me);
        return ApiResponse.success(MasterResponses.DormRoom.from(
                room, assignmentRepository.countActiveByRoomId(roomId)));
    }

    /** 배정. 이미 다른 방을 쓰고 있으면 그 방에서 자동으로 퇴실 처리된다. */
    @PostMapping("/{roomId}/assignments")
    public ApiResponse<MasterResponses.DormOccupant> assign(
            @CurrentAccount AuthPrincipal me, @PathVariable Long roomId,
            @Valid @RequestBody MasterRequests.AssignDorm request) {
        return ApiResponse.success(MasterResponses.DormOccupant.from(
                dormService.assign(roomId, request.enrollmentId(), me)));
    }

    /** 퇴실. 행은 남기고 퇴실 시각만 찍는다 — 누가 언제 어느 방을 썼는지가 남아야 한다. */
    @DeleteMapping("/assignments/students/{enrollmentId}")
    public ApiResponse<Void> release(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId) {
        dormService.release(enrollmentId, me);
        return ApiResponse.empty();
    }
}
