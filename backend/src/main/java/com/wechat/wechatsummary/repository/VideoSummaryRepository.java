package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.VideoSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface VideoSummaryRepository extends JpaRepository<VideoSummary, String> {

    Optional<VideoSummary> findByFileHash(String fileHash);

    @Query("SELECT v FROM VideoSummary v WHERE v.filePath LIKE CONCAT('%/', :uuid, '/%')")
    List<VideoSummary> findByFilePathContainingUuid(@Param("uuid") String uuid);
}
