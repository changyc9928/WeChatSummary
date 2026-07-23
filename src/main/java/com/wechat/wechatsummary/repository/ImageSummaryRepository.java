package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ImageSummaryRepository extends JpaRepository<ImageSummaryEntity, String> {

    Optional<ImageSummaryEntity> findByImageHash(String imageHash);
}
