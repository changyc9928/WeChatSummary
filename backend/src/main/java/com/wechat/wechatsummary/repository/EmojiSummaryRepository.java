package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.EmojiSummaryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EmojiSummaryRepository extends JpaRepository<EmojiSummaryEntity, String> {

    Optional<EmojiSummaryEntity> findByEmojiHash(String emojiHash);

    @Query("SELECT e FROM EmojiSummaryEntity e WHERE e.filePath LIKE CONCAT('%/', :uuid, '/%')")
    List<EmojiSummaryEntity> findByFilePathContainingUuid(@Param("uuid") String uuid);
}
