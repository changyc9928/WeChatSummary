package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.AudioSummary;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioSummaryRepository extends JpaRepository<AudioSummary, Long> {

    Optional<AudioSummary> findByFileHash(String fileHash);
}