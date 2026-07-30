package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImageSummaryRepository extends JpaRepository<ImageSummaryEntity, String> {

    Optional<ImageSummaryEntity> findByImageHash(String imageHash);

    /**
     * Finds image entities where the file_path contains the session/chat UUID directory segment.
     */
    @Query("SELECT i FROM ImageSummaryEntity i WHERE i.filePath LIKE CONCAT('%/', :uuid, '/%')")
    List<ImageSummaryEntity> findByFilePathContainingUuid(@Param("uuid") String uuid);
}