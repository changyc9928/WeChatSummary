package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.ChatAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ChatController {

    @Autowired
    private ChatAnalysisService chatAnalysisService;

    // 💡 将原本的 @RequestParam 改为了路径参数 {uuid}
    @GetMapping("/api/analyze/{uuid}")
    public String analyze(@PathVariable UUID uuid) {
        // 💡 这里我直接帮你传了 uuid。
        // 如果你的 Service 依然需要文件路径，你可以在 Service 内部或者这里，
        // 根据 uuid 去数据库/对象存储里查出对应的 Markdown 文件路径再行处理。
        return chatAnalysisService.analyzeChatLog(uuid);
    }
}