package com.storycreator.tts;

import com.storycreator.web.TtsExportController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ffmpeg 可用性探测的缓存语义（2026-10-16）。
 *
 * <p>背景（用户反馈）：装了 ffmpeg 之后页面一直显示「不可用」，**必须重启后台服务**才能探测到。
 * 真因是原实现把结论永久缓存（{@code volatile Boolean} 只算一次），而 ffmpeg 是运行期才能装上
 * 的外部命令，所以「没装」这个结论必须会过期。
 *
 * <p>本测试用**可控的探测函数**替代真的起进程，因此与「本机到底装没装 ffmpeg」无关，
 * 结论是确定的。逐条守护：
 * <ul>
 *   <li>负结果会在 TTL 后过期 → 装好之后无需重启即可发现</li>
 *   <li>负结果在 TTL 内仍走缓存 → 导出热路径不会被拖成「每段音频 fork 一次」</li>
 *   <li>正值长期缓存 → 同上</li>
 *   <li>{@code refreshFfmpegAvailability()} 必须忽略缓存真探一次</li>
 *   <li>状态端点走的是强制重探，不读那份 TTL 内的旧结论</li>
 *   <li>真实现（起进程那版）不抛异常、同环境结果可复现</li>
 * </ul>
 */
class FfmpegAvailabilityProbeTest {

    /** 测试用的短 TTL，避免真的等 10 秒。 */
    private static final long TTL_MS = 60;

    /** 可控探测函数：记录调用次数，随时可改「有没有装」。 */
    private static final class FakeFfmpeg {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean installed = new AtomicBoolean(false);

        boolean probe() {
            calls.incrementAndGet();
            return installed.get();
        }
    }

    private static Mp3ProcessingService service(FakeFfmpeg fake) {
        return new Mp3ProcessingService(fake::probe, TTL_MS);
    }

    @Test
    @DisplayName("【核心】装好 ffmpeg 后无需重启服务即可探测到")
    void negativeResultExpires_soInstallingFfmpegNeedsNoRestart() throws InterruptedException {
        FakeFfmpeg fake = new FakeFfmpeg();
        Mp3ProcessingService svc = service(fake);

        assertFalse(svc.isFfmpegAvailable(), "一开始没装 → 不可用");
        fake.installed.set(true);                 // 用户 brew install ffmpeg，服务并没有重启
        Thread.sleep(TTL_MS + 60);                // 让负结果过期
        assertTrue(svc.isFfmpegAvailable(), "「没装」不该被永久记住 → 下次询问就能发现");
    }

    @Test
    @DisplayName("负结果在 TTL 内仍走缓存（导出热路径不会进程风暴）")
    void negativeResultIsCachedWithinTtl() {
        FakeFfmpeg fake = new FakeFfmpeg();
        Mp3ProcessingService svc = service(fake);

        assertFalse(svc.isFfmpegAvailable());
        int afterFirst = fake.calls.get();
        for (int i = 0; i < 20; i++) {
            svc.isFfmpegAvailable();
        }
        assertEquals(afterFirst, fake.calls.get(), "TTL 内的重复询问不应反复起进程");
    }

    @Test
    @DisplayName("正值长期缓存")
    void positiveResultIsCached() {
        FakeFfmpeg fake = new FakeFfmpeg();
        fake.installed.set(true);
        Mp3ProcessingService svc = service(fake);

        assertTrue(svc.isFfmpegAvailable());
        for (int i = 0; i < 20; i++) {
            assertTrue(svc.isFfmpegAvailable());
        }
        // 合并音频时每段都会问一次；可用就必须是廉价的布尔读取，不能每次都起进程
        assertEquals(1, fake.calls.get(), "正值应长期缓存，不再重复探测");
    }

    @Test
    @DisplayName("refreshFfmpegAvailability 忽略缓存，强制真探一次并写回")
    void refreshForcesRealProbe() {
        FakeFfmpeg fake = new FakeFfmpeg();
        Mp3ProcessingService svc = service(fake);

        assertFalse(svc.isFfmpegAvailable());     // 第一次探测，缓存「不可用」
        fake.calls.set(0);
        fake.installed.set(true);

        assertTrue(svc.refreshFfmpegAvailability(), "强制重探必须真的再探一次");
        assertEquals(1, fake.calls.get(), "即使仍在 TTL 内也要真探");
        assertTrue(svc.isFfmpegAvailable(), "重探结果要写回缓存");
    }

    @Test
    @DisplayName("状态端点走强制重探，不读 TTL 内的旧结论")
    void statusEndpointDoesNotReadStaleCache() {
        FakeFfmpeg fake = new FakeFfmpeg();
        Mp3ProcessingService svc = service(fake);

        assertFalse(svc.isFfmpegAvailable());     // 页面首次打开时的探测 → 缓存「不可用」
        fake.calls.set(0);
        fake.installed.set(true);                 // 用户装好 ffmpeg

        TtsExportController controller = new TtsExportController();
        controller.setMp3ProcessingService(svc);
        Map<String, Object> status = controller.ffmpegStatus();

        assertEquals(Boolean.TRUE, status.get("available"),
                "页面每次打开都必须真探测，不能读那份「不可用」缓存");
        assertEquals(1, fake.calls.get(), "端点这一下确实触发了新的探测");
    }

    @Test
    @DisplayName("真实现：起进程探测不抛异常，同环境结果可复现")
    void realProbeIsSafeAndStable() {
        Mp3ProcessingService svc = new Mp3ProcessingService();   // 真的跑 `ffmpeg -version`
        boolean first = svc.refreshFfmpegAvailability();
        assertDoesNotThrow(svc::isFfmpegAvailable);
        assertEquals(first, svc.refreshFfmpegAvailability(), "同一环境下两次探测结果应一致");
    }
}
