package com.ttcraft.tiktok;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
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
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TikTokCraft-Net");
        t.setDaemon(true);
        return t;
    });

    private static Path topicsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("tiktokcraft_topics.txt");
    }

    public static String loadSavedTopics() {
        try {
            Path p = topicsFile();
            if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {}
        return "";
    }

    public static void saveTopics(String rawTopics) {
        try {
            Files.createDirectories(FabricLoader.getInstance().getConfigDir());
            Files.writeString(topicsFile(), rawTopics == null ? "" : rawTopics.trim(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public static void fetchFeedAsync(String query, Consumer<List<TikTokVideo>> onOk, Consumer<String> onErr) {
        POOL.submit(() -> {
            try {
                List<TikTokVideo> list = fetchFeed(query);
                if (list.isEmpty()) onErr.accept("Видео не найдены");
                else onOk.accept(list);
            } catch (Exception e) {
                onErr.accept("Ошибка сети: " + e.getMessage());
            }
        });
    }

    private static List<TikTokVideo> fetchFeed(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) return fetchSingleQuery(q);

        // Если поле пустое — грузим по сохранённым темам пользователя ("Моя лента")
        String saved = loadSavedTopics();
        if (!saved.isEmpty()) {
            String[] parts = saved.split(",");
            List<String> valid = new ArrayList<>();
            for (String s : parts) {
                String t = s.trim();
                if (!t.isEmpty()) valid.add(t);
            }
            if (!valid.isEmpty()) {
                Collections.shuffle(valid);
                List<TikTokVideo> combined = new ArrayList<>();
                int maxTopics = Math.min(2, valid.size());
                for (int i = 0; i < maxTopics; i++) {
                    if (i > 0) Thread.sleep(1100); // защита от лимита 1 req/sec
                    combined.addAll(fetchSingleQuery(valid.get(i)));
                }
                Collections.shuffle(combined);
                if (!combined.isEmpty()) return combined;
            }
        }

        // Иначе общие рекомендации UA -> US
        List<TikTokVideo> out = requestAndParse("https://www.tikwm.com/api/feed/list?region=UA&count=15", false, false);
        if (out.isEmpty()) {
            Thread.sleep(1100);
            out = requestAndParse("https://www.tikwm.com/api/feed/list?region=US&count=15", false, false);
        }
        return out;
    }

    private static List<TikTokVideo> fetchSingleQuery(String q) throws Exception {
        boolean isUrl = q.startsWith("http://") || q.startsWith("https://");
        boolean isUser = !isUrl && q.startsWith("@") && q.length() > 1;
        String apiUrl;
        if (isUrl) {
            apiUrl = "https://www.tikwm.com/api/?url=" + enc(q);
        } else if (isUser) {
            apiUrl = "https://www.tikwm.com/api/user/posts?unique_id=" + enc(q) + "&count=15&cursor=0";
        } else {
            apiUrl = "https://www.tikwm.com/api/feed/search?keywords=" + enc(q) + "&count=15&cursor=0";
        }
        return requestAndParse(apiUrl, isUrl, !isUrl);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static List<TikTokVideo> requestAndParse(String apiUrl, boolean isUrl, boolean hasVideosField) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        List<TikTokVideo> out = new ArrayList<>();
        if (!root.has("data") || root.get("data").isJsonNull()) return out;

        if (isUrl) {
            TikTokVideo v = parseItem(root.getAsJsonObject("data"));
            if (v != null) out.add(v);
            return out;
        }
        JsonArray arr = hasVideosField
                ? root.getAsJsonObject("data").getAsJsonArray("videos")
                : root.getAsJsonArray("data");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                TikTokVideo v = parseItem(el.getAsJsonObject());
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    private static TikTokVideo parseItem(JsonObject o) {
        String play = str(o, "play");
        if (play.isEmpty()) return null;
        if (!play.startsWith("http")) play = "https://www.tikwm.com" + play;
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
                Path tmp = dir.resolve(safeId + ".part");
                HttpRequest req = HttpRequest.newBuilder(URI.create(video.playUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", UA).header("Referer", "https://www.tiktok.com/").GET().build();
                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    try (InputStream in = resp.body()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                    Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
                    video.localPath = finalFile.toAbsolutePath().toString();
                    trimCache(dir);
                } else { video.failed = true; }
            } catch (Exception e) { video.failed = true; }
            finally { video.downloading = false; }
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
