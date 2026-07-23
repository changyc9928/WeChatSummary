package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.AudioSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AudioSummaryRepository extends JpaRepository<AudioSummary, String> {

    Optional<AudioSummary> findByFileHash(String fileHash);

    /**
     * Finds audio entities where the file_path contains the session/chat UUID directory segment.
     */
    @Query("SELECT a FROM AudioSummary a WHERE a.filePath LIKE CONCAT('%/', :uuid, '/%')")
    List<AudioSummary> findByFilePathContainingUuid(@Param("uuid") String uuid);
}