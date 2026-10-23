package com.storycreator.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「资源共享」的 HTTP 端到端测试。
 *
 * <p>与 {@link com.storycreator.shared.SharedStorageServiceTest} 分工：
 * 那个测的是存储层的拒绝规则（纯文件系统），这里测**接口层**——
 * 页面能不能出来、multipart 能不能进、下载头对不对、越界请求在 HTTP 上回什么状态码。
 *
 * <p>存储根目录用 {@link DynamicPropertySource} 指到临时目录，
 * 既不碰 {@code ./data}，也不会和用户真实共享目录串数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shared_it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
class SharedResourceIntegrationTest {

    /** 一次测试运行共用一个临时根，避免每个用例都重建目录。 */
    private static final Path STORAGE_ROOT;

    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("sc-shared-it");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("STORY_DB_PATH", STORAGE_ROOT::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static ByteArrayResource filePart(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private ResponseEntity<String> upload(String dirPath, String filename, String content,
                                          String origin) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("path", dirPath);
        body.add("files", filePart(filename, content));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        return restTemplate.postForEntity(url("/shared/api/upload"), new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postJson(String path, String json, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        return restTemplate.postForEntity(url(path), new HttpEntity<>(json, headers), String.class);
    }

    // ==================== 页面 ====================

    @Test
    void sharedPage_rendersSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/shared"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("静态页：挂共享导航栏与公共脚本")
                .contains("id=\"site-nav\"")
                .contains("/js/common.js")
                .contains("/js/nav.js")
                .as("页面骨架四要素：上传入口、新建文件夹、面包屑容器、拖拽区")
                .contains("btnUpload")
                .contains("btnMkdir")
                .contains("sc-share-crumbs")
                .contains("dropZone")
                .as("数据接口走 /shared/api（前缀常量 + 各动作分别拼接）")
                .contains("'/shared/api'")
                .contains("'/list?path='")
                .contains("'/upload'")
                .contains("'/download?path='")
                .contains("'/mkdir'")
                .as("页面上不再提供删除入口（后端端点保留，供运维脚本用）")
                .doesNotContain("data-act=\"del\"")
                .doesNotContain("'/delete'")
                .as("首屏走异步 fetch（不阻塞 HTML 解析；上传进度用的是异步 XHR，不是同步拉数据）")
                .contains("fetch(API + '/list?path='")
                .as("引导不用预置 JSON：页面一进来就 fetch 列表，先摆骨架")
                .contains("sc-skel");
    }

    // ==================== 列表 ====================

    @Test
    void list_returnsRootListing() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/shared/api/list"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"path\":\"\"")
                .contains("\"crumbs\"")
                .contains("\"items\"")
                .contains("\"canGoUp\":false")
                .contains("\"dirCount\"")
                .contains("\"totalBytes\"");
    }

    @Test
    void list_itemsCarryCreatedAtForSorting() {
        // 前端要按「创建日期 / 修改日期」排序，两个时间都必须随列表下发
        upload("", "it-created-at.txt", "x", null);
        ResponseEntity<String> response = restTemplate.getForEntity(url("/shared/api/list"), String.class);
        assertThat(response.getBody())
                .as("每条 item 都要带 createdAt / modifiedAt，字段缺失会让前端排序整列排在最后")
                .contains("\"createdAt\":\"")
                .contains("\"modifiedAt\":\"");
    }

    // ==================== 上传 → 列表 → 下载 → 删除 ====================

    @Test
    void uploadListDownloadDelete_roundTrip() {
        String name = "it-roundtrip.txt";
        String content = "资源共享 round-trip，中文内容";

        ResponseEntity<String> up = upload("", name, content, null);
        assertThat(up.getStatusCode()).as("上传应 200，响应=%s", up.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(up.getBody()).contains("\"count\":1").contains(name);

        ResponseEntity<String> list = restTemplate.getForEntity(url("/shared/api/list"), String.class);
        assertThat(list.getBody()).contains(name);

        ResponseEntity<byte[]> dl = restTemplate.getForEntity(
                url("/shared/api/download?path=" + URLEncoder.encode(name, StandardCharsets.UTF_8)),
                byte[].class);
        assertThat(dl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(dl.getBody(), StandardCharsets.UTF_8)).isEqualTo(content);
        String disposition = dl.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition)
                .as("必须强制下载（attachment），别让共享目录里的 html/svg 在本地域名下被渲染")
                .startsWith("attachment");
        assertThat(dl.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(dl.getHeaders().getContentLength()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);

        ResponseEntity<String> del = postJson("/shared/api/delete",
                "{\"path\":\"" + name + "\"}", null);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<byte[]> gone = restTemplate.getForEntity(
                url("/shared/api/download?path=" + URLEncoder.encode(name, StandardCharsets.UTF_8)),
                byte[].class);
        assertThat(gone.getStatusCode()).as("删掉之后不该还能下载").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadToSubDirectory_thenNavigate() {
        assertThat(postJson("/shared/api/mkdir", "{\"path\":\"it-docs\"}", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> up = upload("it-docs", "note.md", "# 笔记", null);
        assertThat(up.getStatusCode()).as("上传到子目录应 200，响应=%s", up.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(up.getBody()).contains("it-docs/note.md");

        ResponseEntity<String> sub = restTemplate.getForEntity(url("/shared/api/list?path=it-docs"), String.class);
        assertThat(sub.getBody())
                .contains("\"path\":\"it-docs\"")
                .contains("\"parent\":\"\"")
                .contains("\"canGoUp\":true")
                .contains("note.md");

        // 清理：子目录非空时不让删
        ResponseEntity<String> conflict = postJson("/shared/api/delete", "{\"path\":\"it-docs\"}", null);
        assertThat(conflict.getStatusCode())
                .as("非空目录删除应 409（不做递归删除）").isEqualTo(HttpStatus.CONFLICT);
        assertThat(postJson("/shared/api/delete", "{\"path\":\"it-docs/note.md\"}", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(postJson("/shared/api/delete", "{\"path\":\"it-docs\"}", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ==================== 越界：HTTP 层必须是 4xx，绝不能把文件送出去 ====================

    @Test
    void download_rejectsTraversal() {
        for (String bad : new String[]{
                "..%2Fpom.xml",
                "..%2F..%2Fetc%2Fpasswd",
                "%2Fetc%2Fpasswd"}) {
            // 用 URI 直传：String 形式会被 RestTemplate 二次编码（%2F → %252F），
            // 后端收到的就不是穿越串了，测试等于没测
            ResponseEntity<byte[]> res = restTemplate.getForEntity(
                    java.net.URI.create(url("/shared/api/download?path=" + bad)), byte[].class);
            assertThat(res.getStatusCode().is4xxClientError())
                    .as("越界下载必须 4xx（path=%s），实际 %s", bad, res.getStatusCode())
                    .isTrue();
        }
    }

    @Test
    void upload_rejectsTraversalInDirPath() {
        ResponseEntity<String> res = upload("../evil", "a.txt", "x", null);
        assertThat(res.getStatusCode()).as("目标目录带 ../ 应 400，响应=%s", res.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_rejectsTraversalAndAbsolutePath() {
        assertThat(restTemplate.getForEntity(
                java.net.URI.create(url("/shared/api/list?path=..%2F..")), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.getForEntity(
                java.net.URI.create(url("/shared/api/list?path=%2Fetc")), String.class)
                .getStatusCode())
                .as("绝对路径要 400（用 URI 直传，避免 RestTemplate 二次编码成 %252F）")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void download_missingFileIs404() {
        assertThat(restTemplate.getForEntity(url("/shared/api/download?path=nope-xyz.txt"), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 同源校验（挡跨站表单偷偷写文件） ====================

    @Test
    void crossSiteOrigin_isRejected() {
        ResponseEntity<String> res = upload("", "csrf.txt", "x", "http://evil.example");
        assertThat(res.getStatusCode())
                .as("带外站 Origin 的上传应 403，响应=%s", res.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sameOrigin_isAllowed() {
        String origin = "http://localhost:" + port;
        ResponseEntity<String> res = upload("", "same-origin.txt", "ok", origin);
        assertThat(res.getStatusCode()).as("同源应放行，响应=%s", res.getBody()).isEqualTo(HttpStatus.OK);
        // 清理
        postJson("/shared/api/delete", "{\"path\":\"same-origin.txt\"}", null);
    }

    // ==================== 首页入口 ====================

    @Test
    void homeHasSharedModuleCard() {
        ResponseEntity<String> home = restTemplate.getForEntity(url("/"), String.class);
        assertThat(home.getBody())
                .as("首页模块墙要有【资源共享】入口")
                .contains(">资源共享</h5>")
                .contains("href=\"/shared\"");
    }
}
