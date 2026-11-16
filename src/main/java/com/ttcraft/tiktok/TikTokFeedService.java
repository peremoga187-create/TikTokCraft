package com.ttcraft.tiktok;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class TikTokFeedService {
    private static final Logger LOG = LoggerFactory.getLogger("TikTokCraft");
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TikTokCraft-Net");
        t.setDaemon(true);
        return t;
    });

    public static void fetchFeedAsync(String query, Consumer<List<TikTokVideo>> onOk, Consumer<String> onErr) {
        POOL.submit(() -> {
            try {
                List<TikTokVideo> list = fetchFeed(query);
                if (list.isEmpty()) onErr.accept("Пустой ответ от TikTok API");
                else onOk.accept(list);
            } catch (Exception e) {
                LOG.error("Feed fetch error", e);
                onErr.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static List<TikTokVideo> fetchFeed(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        boolean isUrl = q.startsWith("http://") || q.startsWith("https://");
        boolean isUser = !isUrl && q.startsWith("@") && q.length() > 1;
        boolean isSearch = !isUrl && !isUser && !q.isEmpty();

        String apiUrl = isUrl
                ? "https://www.tikwm.com/api/?url=" + enc(q)
                : isUser
                ? "https://www.tikwm.com/api/user/posts?unique_id=" + enc(q) + "&count=15&cursor=0"
                : isSearch
                ? "https://www.tikwm.com/api/feed/search?keywords=" + enc(q) + "&count=15&cursor=0"
                : "https://www.tikwm.com/api/feed/list?region=UA&count=15";

        List<TikTokVideo> out = requestAndParse(apiUrl, isUrl);
        if (out.isEmpty() && !isUrl && !isUser && !isSearch) {
            Thread.sleep(1150);
            out = requestAndParse("https://www.tikwm.com/api/feed/list?region=US&count=15", false);
        }
        return out;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static List<TikTokVideo> requestAndParse(String apiUrl, boolean isUrl) throws Exception {
        LOG.info("Requesting TikTok API: {}", apiUrl);
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.info("API HTTP {} (len={})", resp.statusCode(), resp.body().length());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code != 0) {
            String msg = str(root, "msg");
            LOG.warn("API code={}, msg={}", code, msg);
            throw new RuntimeException("API: " + msg);
        }

        List<TikTokVideo> out = new ArrayList<>();
        if (!root.has("data") || root.get("data").isJsonNull()) return out;
        JsonElement data = root.get("data");

        if (isUrl && data.isJsonObject()) {
            TikTokVideo v = parseItem(data.getAsJsonObject());
            if (v != null) out.add(v);
            return out;
        }

        JsonArray arr = data.isJsonArray() ? data.getAsJsonArray()
                : (data.isJsonObject() && data.getAsJsonObject().has("videos") && data.getAsJsonObject().get("videos").isJsonArray())
                ? data.getAsJsonObject().getAsJsonArray("videos") : null;
        if (arr == null) return out;

        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                TikTokVideo v = parseItem(el.getAsJsonObject());
                if (v != null) out.add(v);
            }
        }
        LOG.info("Parsed {} videos", out.size());
        return out;
    }

    private static TikTokVideo parseItem(JsonObject o) {
        String play = str(o, "play");
        if (play.isEmpty()) return null;
        if (play.startsWith("//")) play = "https:" + play;
        else if (play.startsWith("/")) play = "https://www.tikwm.com" + play;

        String id = str(o, "video_id");
        if (id.isEmpty()) id = str(o, "id");
        if (id.isEmpty()) id = String.valueOf(Math.abs(play.hashCode()));
        String author = "tiktok";
        if (o.has("author") && o.get("author").isJsonObject()) {
            JsonObject a = o.getAsJsonObject("author");
            String uid = str(a, "unique_id"), nick = str(a, "nickname");
            author = !uid.isEmpty() ? "@" + uid : (!nick.isEmpty() ? nick : "tiktok");
        }
        long likes = o.has("digg_count") && !o.get("digg_count").isJsonNull() ? o.get("digg_count").getAsLong() : 0;
        return new TikTokVideo(id, author, str(o, "title"), play, likes);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    public static void ensureDownloadedAsync(TikTokVideo video) {
        if (video == null || video.localPath != null || video.downloading || video.failed) return;
        video.downloading = true;
        POOL.submit(() -> {
            try {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("tiktok_cache");
                Files.createDirectories(dir);
                String safeId = video.id.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path finalFile = dir.resolve(safeId + ".mp4");
                if (Files.exists(finalFile) && Files.size(finalFile) > 8192) {
                    video.localPath = finalFile.toAbsolutePath().toString();
                    return;
                }
                LOG.info("Downloading video {} from {}", video.id, video.playUrl);
                Path tmp = dir.resolve(safeId + ".part");
                HttpRequest req = HttpRequest.newBuilder(URI.create(video.playUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", UA).header("Referer", "https://www.tiktok.com/").GET().build();
                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    try (InputStream in = resp.body()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                    Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
                    video.localPath = finalFile.toAbsolutePath().toString();
                    LOG.info("Downloaded {} ({} bytes)", safeId, Files.size(finalFile));
                    trimCache(dir);
                } else {
                    video.errorMsg = "HTTP " + resp.statusCode();
                    video.failed = true;
                    LOG.warn("Download failed {} -> HTTP {}", video.id, resp.statusCode());
                }
            } catch (Exception e) {
                video.errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                video.failed = true;
                LOG.error("Download exception for " + video.id, e);
            } finally { video.downloading = false; }
        });
    }

    private static void trimCache(Path dir) {
        try (var s = Files.list(dir)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0L; }
                    })).toList();
            for (int i = 0; i < files.size() - 20; i++) Files.deleteIfExists(files.get(i));
        } catch (Exception ignored) {}
    }
}
