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
     * Finds an image entity whose stored file path contains the given fragment (e.g. an image
     * md5 that is embedded in the on-disk file name). Enables resolving referenced images whose
     * lookup key is an md5 rather than a path-derived hash.
     */
    Optional<ImageSummaryEntity> findByFilePathContaining(String fragment);

    /**
     * Finds image entities where the file_path contains the session/chat UUID directory segment.
     */
    @Query("SELECT i FROM ImageSummaryEntity i WHERE i.filePath LIKE CONCAT('%/', :uuid, '/%')")
    List<ImageSummaryEntity> findByFilePathContainingUuid(@Param("uuid") String uuid);
}