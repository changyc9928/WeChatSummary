package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.MediaProducerService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/preprocess")
public class PreprocessController {

    private final MediaProducerService producerService;

    public PreprocessController(MediaProducerService producerService) {
        this.producerService = producerService;
    }

    @PostMapping("/{uuid}")
    public String preprocess(@PathVariable String uuid)
        throws Exception {

        producerService.preprocess(uuid);

        return "Submitted preprocessing for " + uuid;
    }
}
