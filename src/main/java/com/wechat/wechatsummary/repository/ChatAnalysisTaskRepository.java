package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ChatAnalysisTaskRepository extends JpaRepository<ChatAnalysisTask, UUID> {

    /**
     * Strictly responsible for logging internal service engine errors.
     * No state tracking machine variables or structural assumptions belong here.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chat_analysis_task (id, status, error_message, updated_at)
        VALUES (:id, 'FAILED', :errorMessage, NOW())
        ON CONFLICT (id) 
        DO UPDATE SET status = 'FAILED', error_message = :errorMessage, updated_at = NOW()
        """, nativeQuery = true)
    void logFailureReason(@Param("id") UUID id, @Param("errorMessage") String errorMessage);
}