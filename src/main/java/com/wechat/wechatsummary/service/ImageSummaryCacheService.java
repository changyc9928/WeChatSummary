package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageSummaryCacheService {

    private final ImageSummaryRepository imageSummaryRepository;

    /**
     * 🛡️ 高并发抗压版：获取图片总结 * 💡 针对【缓存击穿】：添加了 sync = true。当大量请求并发查同一张失效图片时， Spring 会加锁强制其排队，只有 1
     * 个线程去查库，其余线程原地等待并直接复用 Redis 结果。 * 💡 针对【缓存穿透】：移除了 unless 条件。如果数据库中没有对应的图片（返回
     * Optional.empty()）， Redis 也会把这个“空结果”缓存起来。下次再来查这个不存在的 Hash，Redis 直接拦截并返回空，不再打扰 Postgres。
     */
    @Cacheable(cacheNames = "image_summary", key = "#hash", sync = true)
    public Optional<String> getSummary(String hash) {
        log.info("【Redis 缓存未命中】开始检索数据源，Hash: {}", hash);

        ImageSummaryEntity dbResult = imageSummaryRepository.findByImageHash(hash);
        if (dbResult != null) {
            log.info("【Postgres 检索成功】Hash: {} 存在，正在将其自动回填至 Redis...", hash);
            return Optional.ofNullable(dbResult.getSummary());
        }

        log.warn("【Postgres 检索失败】未找到 Hash: {}，将在 Redis 中缓存空标记以防穿透漏洞", hash);
        return Optional.empty();
    }

    /**
     * 💡 当 AI 线程异步生成了全新的图片总结，并完成 Postgres 落库后， 调用此方法，强行将最新结果同步推进 Redis 缓存。
     */
    @CachePut(cacheNames = "image_summary", key = "#hash")
    public Optional<String> putSummary(String hash, String summary) {
        log.info("【Redis 强行更新】接收到 AI 生成的最新数据，主动更新缓存。Hash: {}", hash);
        // 💡 返回 Optional 保持与 getSummary 的数据类型一致，防止 Redis 反序列化时发生类型转换异常
        return Optional.ofNullable(summary);
    }

    /**
     * 💡 强制失效缓存
     */
    @CacheEvict(cacheNames = "image_summary", key = "#hash")
    public void evict(String hash) {
        log.info("【Redis 缓存主动销毁】清空缓存。Hash: {}", hash);
    }
}