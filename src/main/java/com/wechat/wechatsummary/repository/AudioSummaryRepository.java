package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.AudioSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AudioSummaryRepository extends JpaRepository<AudioSummary, Long> {
    Optional<AudioSummary> findByFileHash(String fileHash);
}