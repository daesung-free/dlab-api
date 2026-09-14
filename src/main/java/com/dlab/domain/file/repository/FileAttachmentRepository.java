package com.dlab.domain.file.repository;

import com.dlab.domain.file.entity.FileAttachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FileAttachmentRepository extends JpaRepository<FileAttachment, Long> {

    @Query("""
            SELECT f FROM FileAttachment f
            WHERE f.id = :id AND f.deleted = false
            """)
    Optional<FileAttachment> findActiveById(Long id);

    /** 대상에 붙은 파일. 화면이 첨부 목록을 그릴 때 쓴다. */
    @Query("""
            SELECT f FROM FileAttachment f
            WHERE f.ownerType = :ownerType
              AND f.ownerId = :ownerId
              AND f.deleted = false
            ORDER BY f.id ASC
            """)
    List<FileAttachment> findByOwner(String ownerType, Long ownerId);
}
