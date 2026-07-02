package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisCacheService {

    private final ChatAnalysisTaskRepository taskRepository;

    /**
     * 🛡️ 高并发抗压轮询版：查询任务状态与结果 * 💡 针对【防击穿】：保留 sync = true，前端疯狂轮询时拦截并发。 💡 针对【防穿透与状态上演变】： -
     * 'PROCESSING'.equals(...) -> 💡 核心：如果还在处理中，绝对不进 Redis，确保前端能实时轮询到最新进度！ - #result == null ||
     * !#result.isPresent() -> 💡 核心：如果根本没这个任务，不进 Redis，交给 Controller 返回 404。 - 此时，SUCCESS 和
     * FAILED（终态）都会被顺利写入 Redis。即便任务失败了，后续轮询也是直接命中 Redis 缓存返回错误信息，不再惊动 Postgres 数据库！
     */
    @Cacheable(
        cacheNames = "chat_analysis",
        key = "#uuid.toString()",
        sync = true,
        unless = "#result == null || !#result.isPresent() || 'PROCESSING'.equals(#result.get().getStatus())"
    )
    public Optional<ChatAnalysisTask> getCachedTask(UUID uuid) {
        log.info("【Redis 缓存未命中/不满足缓存条件】从 PostgreSQL 数据库读取任务详情，UUID: {}",
            uuid);
        return taskRepository.findById(uuid);
    }

    /**
     * 💡 清除缓存方法 当重新触发分析时，Controller 会主动调用这里闪击销毁 Redis 里的历史终态缓存
     */
    @CacheEvict(cacheNames = "chat_analysis", key = "#uuid.toString()")
    public void evictCache(UUID uuid) {
        log.info("【Redis 缓存主动销毁】清空任务缓存。UUID: {}", uuid);
    }
}