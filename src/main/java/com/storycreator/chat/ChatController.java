package com.storycreator.chat;

import com.storycreator.core.domain.ModelType;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class ChatController {

    private final ChatService chatService;
    private final AiModelConfigRepository configRepository;

    public ChatController(ChatService chatService, AiModelConfigRepository configRepository) {
        this.chatService = chatService;
        this.configRepository = configRepository;
    }

    @GetMapping("/chat")
    public String chatPage(Model model) {
        var sessions = chatService.listSessions();
        model.addAttribute("sessions", sessions);

        List<AiModelConfigEntity> textConfigs = configRepository.findByActiveTrueAndModelType(ModelType.TEXT);
        List<AiModelConfigEntity> ttsConfigs = configRepository.findByActiveTrueAndModelType(ModelType.TTS);
        List<AiModelConfigEntity> imageConfigs = configRepository.findByActiveTrueAndModelType(ModelType.IMAGE);

        model.addAttribute("textConfigs", textConfigs);
        model.addAttribute("ttsConfigs", ttsConfigs);
        model.addAttribute("imageConfigs", imageConfigs);

        return "chat";
    }
}
