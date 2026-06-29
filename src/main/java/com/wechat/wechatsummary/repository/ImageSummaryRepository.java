package com.wechat.wechatsummary.repository;

import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ImageSummaryRepository extends JpaRepository<ImageSummaryEntity, String> {

    ImageSummaryEntity findByImageHash(String imageHash);
}
