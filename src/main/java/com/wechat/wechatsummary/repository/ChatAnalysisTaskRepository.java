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
     * 💡 成功时：将本地 TXT 报告的绝对路径写入 result 字段，并将状态强刷为 SUCCESS
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chat_analysis_task (id, status, result, error_message, updated_at)
        VALUES (:id, 'SUCCESS', :resultPath, NULL, NOW())
        ON CONFLICT (id) 
        DO UPDATE SET status = 'SUCCESS', result = :resultPath, error_message = NULL, updated_at = NOW()
        """, nativeQuery = true)
    void updateResult(@Param("id") UUID id, @Param("resultPath") String resultPath);

    /**
     * 💡 纯状态更新：单单把任务状态改为 SUCCESS（作为 Service 里的语义双保险兜底）
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chat_analysis_task (id, status, updated_at)
        VALUES (:id, 'SUCCESS', NOW())
        ON CONFLICT (id) 
        DO UPDATE SET status = 'SUCCESS', updated_at = NOW()
        """, nativeQuery = true)
    void updateStatusToSuccess(@Param("id") UUID id);

    /**
     * 失败时：记录错误状态与异常堆栈信息
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chat_analysis_task (id, status, error_message, updated_at)
        VALUES (:id, 'FAILED', :errorMessage, NOW())
        ON CONFLICT (id) 
        DO UPDATE SET status = 'FAILED', error_message = :errorMessage, updated_at = NOW()
        """, nativeQuery = true)
    void updateStatusToFailed(@Param("id") UUID id, @Param("errorMessage") String errorMessage);
}