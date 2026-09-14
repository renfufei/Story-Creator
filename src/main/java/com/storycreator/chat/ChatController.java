package com.storycreator.chat;

import com.storycreator.core.domain.ModelType;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天页（静态页 + 引导 JSON）。
 *
 * <p>页面本身是 {@code static/pages/chat.html}，URL 保持不变；
 * 引导数据由 {@code GET /chat/data} 提供。会话/消息/流式接口仍由 {@link ChatApiController} 提供。
 */
@Controller
public class ChatController {

    private final ChatService chatService;
    private final AiModelConfigRepository configRepository;

    public ChatController(ChatService chatService, AiModelConfigRepository configRepository) {
        this.chatService = chatService;
        this.configRepository = configRepository;
    }

    @GetMapping("/chat")
    public String chatPage() {
        return "forward:/pages/chat.html";
    }

    /** 页面引导数据：会话列表 + 三类模型配置。 */
    @GetMapping("/chat/data")
    @ResponseBody
    public Map<String, Object> data() {
        Map<String, Object> m = new HashMap<>();
        m.put("sessions", chatService.listSessions().stream()
                .map(s -> {
                    Map<String, Object> x = new HashMap<>();
                    x.put("id", s.getId());
                    x.put("title", s.getTitle());
                    return x;
                })
                .toList());
        m.put("textConfigs", configsByType(ModelType.TEXT));
        m.put("ttsConfigs", configsByType(ModelType.TTS));
        m.put("imageConfigs", configsByType(ModelType.IMAGE));
        return m;
    }

    private List<Map<String, Object>> configsByType(ModelType type) {
        return configRepository.findByActiveTrueAndModelType(type).stream()
                .map(this::configToMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> configToMap(AiModelConfigEntity c) {
        Map<String, Object> x = new HashMap<>();
        x.put("id", c.getId());
        x.put("displayName", c.getDisplayName() == null ? "" : c.getDisplayName());
        x.put("modelId", c.getModelId() == null ? "" : c.getModelId());
        return x;
    }
}
