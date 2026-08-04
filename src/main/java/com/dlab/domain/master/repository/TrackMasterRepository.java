package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.TrackMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackMasterRepository extends JpaRepository<TrackMaster, Long> {

    List<TrackMaster> findByDeletedFalseOrderByNameAsc();

    boolean existsByNameAndDeletedFalse(String name);
}
