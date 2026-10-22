package com.storycreator.web;

import com.storycreator.shared.SharedStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「资源共享」模块 —— 浏览器端的文件浏览 / 上传 / 下载 / 删除。
 *
 * <p>页面 {@code /shared}，数据接口都在 {@code /shared/api/*} 下。
 * 所有磁盘操作都经 {@link SharedStorageService}，它保证**只**碰
 * {@code ${STORY_DB_PATH}/shared} 这一棵子树（路径穿越、绝对路径、符号链接逃逸都在那里拦掉）。
 * 换句话说：本类不做任何路径判断，也**不允许**直接把请求参数拼成 {@code Path} ——
 * 少一个地方解析路径，就少一处越界的机会。
 *
 * <h3>下载的响应头为什么这么设</h3>
 * <ul>
 *   <li>{@code Content-Disposition: attachment} + {@code filename*=UTF-8''...}：中文名不乱码，
 *       且强制下载而不是在页面里渲染 —— 共享目录里可能有人放 {@code .html} 或 {@code .svg}，
 *       内联渲染等于给我们自己的域名开了一个存储型 XSS 的口子。</li>
 *   <li>{@code X-Content-Type-Options: nosniff}：别让浏览器「猜」类型。</li>
 *   <li>可渲染类型（html / svg / xml / js）一律降级成 {@code application/octet-stream}，
 *       双保险。</li>
 *   <li>{@code Cache-Control: no-store}：同名文件删了再传不该命中旧缓存。</li>
 * </ul>
 */
@Controller
@RequestMapping("/shared")
public class SharedResourceController {

    private static final Logger log = LoggerFactory.getLogger(SharedResourceController.class);

    /**
     * 这几种类型即使被浏览器内联打开就有脚本执行能力。
     * 探测到它们时一律按二进制下载，不给「万一 Content-Disposition 没生效」留后路。
     */
    private static final Set<String> UNSAFE_INLINE_TYPES = Set.of(
            "text/html", "application/xhtml+xml",
            "image/svg+xml",
            "text/xml", "application/xml",
            "application/javascript", "text/javascript", "application/x-javascript");

    private final SharedStorageService storage;

    public SharedResourceController(SharedStorageService storage) {
        this.storage = storage;
    }

    /** 页面本身（静态 HTML + Ajax，走 forward，跟其它页一致）。 */
    @GetMapping
    public String page() {
        return "forward:/pages/shared.html";
    }

    /** 目录列表 + 面包屑 + 统计，一次请求全给前端。 */
    @GetMapping("/api/list")
    @ResponseBody
    public SharedStorageService.Listing list(
            @RequestParam(name = "path", required = false, defaultValue = "") String path) {
        return storage.list(path);
    }

    /** 上传（支持一次多选）。字段名固定 {@code files}，目标目录用 {@code path}。 */
    @PostMapping("/api/upload")
    @ResponseBody
    public Map<String, Object> upload(
            @RequestParam(name = "path", required = false, defaultValue = "") String path,
            @RequestParam(name = "files", required = false) MultipartFile[] files,
            HttpServletRequest request) {
        assertSameOrigin(request);
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("没有收到文件");
        }
        List<SharedStorageService.Entry> saved = storage.store(path, Arrays.asList(files));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("count", saved.size());
        body.put("saved", saved);
        return body;
    }

    /** 下载。走 GET，可以直接给 {@code <a href>} / 浏览器原生下载，不用 fetch 拼 Blob。 */
    @GetMapping("/api/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) {
        SharedStorageService.StoredFile file = storage.open(path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(contentTypeOf(file.path()))
                .contentLength(file.size())
                .body(new FileSystemResource(file.path()));
    }

    /** 删除（文件或空目录；非空目录返回 409）。 */
    @PostMapping("/api/delete")
    @ResponseBody
    public Map<String, Object> delete(@RequestBody(required = false) Map<String, String> body,
                                      HttpServletRequest request) {
        assertSameOrigin(request);
        String path = body == null ? null : body.get("path");
        storage.delete(path);
        return Map.of("status", "ok");
    }

    /** 新建文件夹（父目录必须已存在）。 */
    @PostMapping("/api/mkdir")
    @ResponseBody
    public Map<String, Object> mkdir(@RequestBody(required = false) Map<String, String> body,
                                     HttpServletRequest request) {
        assertSameOrigin(request);
        String path = body == null ? null : body.get("path");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("entry", storage.mkdir(path));
        return out;
    }

    // ==================== 内部工具 ====================

    /**
     * 轻量同源校验（只挡写操作）。
     *
     * <p>本应用没有登录态，理论上别人无法「代表你」调用接口；但一个恶意网页完全可以用
     * 跨站表单把自己的文件 POST 到你本机的 1888 端口。带 {@code Origin} 且与本站不同源的一律拒绝。
     * 非浏览器客户端（curl、探针）通常不带 {@code Origin}，放行 —— 这里是纵深防御，不是鉴权。
     */
    private static void assertSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin)) {
            return;
        }
        String host;
        try {
            host = URI.create(origin).getHost();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "跨站请求被拒绝");
        }
        if (host == null || !host.equalsIgnoreCase(request.getServerName())) {
            log.warn("拒绝跨站写入请求: origin={} host={}", origin, request.getServerName());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "跨站请求被拒绝");
        }
    }

    private static MediaType contentTypeOf(Path file) {
        String probed = null;
        try {
            probed = Files.probeContentType(file);
        } catch (Exception ignored) {
            // 探测失败就按二进制发
        }
        if (probed == null || probed.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(probed);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        if (UNSAFE_INLINE_TYPES.contains(type.getType() + "/" + type.getSubtype())) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return type;
    }
}
