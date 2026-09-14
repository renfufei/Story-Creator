package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.txtimport.ChapterSplitConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TXT 导入页（静态页 + 引导 JSON）。
 *
 * <p>页面本身是 {@code static/pages/txt-import.html}，URL 保持不变；
 * 引导数据由 {@code GET /import/txt/data} 提供，静态页在 Alpine 解析前用同步 XHR 注入全局变量。
 * 业务接口（上传/分割/逆向工程/SSE）由 {@link TxtImportApiController} 提供。
 */
@Controller
@RequestMapping("/import/txt")
public class TxtImportPageController {

    private final ChapterSplitConfigService configService;
    private final AiModelConfigRepository aiModelConfigRepository;

    public TxtImportPageController(ChapterSplitConfigService configService,
                                   AiModelConfigRepository aiModelConfigRepository) {
        this.configService = configService;
        this.aiModelConfigRepository = aiModelConfigRepository;
    }

    @GetMapping
    public String page() {
        return "forward:/pages/txt-import.html";
    }

    /** 页面引导数据：启用的分割配置 / 题材枚举 / AI 模型列表。 */
    @GetMapping("/data")
    @ResponseBody
    public Map<String, Object> data() {
        Map<String, Object> m = new HashMap<>();
        List<Map<String, Object>> configs = configService.listEnabled().stream()
                .map(this::configToMap)
                .toList();
        m.put("splitConfigs", configs);
        m.put("genres", Arrays.stream(Genre.values())
                .map(g -> Map.of("name", g.name(), "displayName", g.getDisplayName()))
                .toList());
        m.put("modelConfigs", aiModelConfigRepository.findAll().stream()
                .map(c -> Map.of(
                        "id", c.getId(),
                        "displayName", c.getDisplayName() == null ? "" : c.getDisplayName(),
                        "provider", c.getProvider() == null ? "" : c.getProvider()))
                .toList());
        return m;
    }

    private Map<String, Object> configToMap(ChapterSplitConfigEntity c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("description", c.getDescription() == null ? "" : c.getDescription());
        m.put("builtin", c.isBuiltin());
        m.put("enabled", c.isEnabled());
        return m;
    }
}
