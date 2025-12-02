package aphler.controller;

import aphler.pojo.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.corundumstudio.socketio.SocketIOServer;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@CrossOrigin
@RestController
@RequestMapping("/api")
public class WebController {

    public static final int TYPE_MOVE = 1;
    public static final int TYPE_LIKE = 2;

    private final StringRedisTemplate redisTemplate;
    private final SocketIOServer socketServer;

    public WebController(StringRedisTemplate redisTemplate, SocketIOServer socketServer) {
        this.redisTemplate = redisTemplate;
        this.socketServer = socketServer;
    }

    @Value("${uploadPath}")
    private String uploadPath;

    // 上传接口
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> upload(@RequestPart("file") MultipartFile file, @RequestParam("type") Integer type) throws IOException {
        if (file == null || file.isEmpty()) {
            return R.fail("文件为空");
        }
        if (type == null || (type != TYPE_MOVE && type != TYPE_LIKE)) {
            return R.fail("类型不合法");
        }
        // 确保目录存在
        Path dir = Paths.get(uploadPath);
        Files.createDirectories(dir);
        String original = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String uuid = UUID.randomUUID().toString();
        String filename = uuid + "_" + original;
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());
        String fileUrl = target.toString();

        // 计算排序（使用一个计数器键）
        Long order = redisTemplate.opsForValue().increment("files:order:counter");
        if (order == null) order = 1L;

        // 保存文件信息到 Redis Hash
        String key = "file:" + uuid;
        Map<String, String> map = new HashMap<>();
        map.put("id", uuid);
        map.put("path", fileUrl);
        map.put("type", String.valueOf(type));
        map.put("order", String.valueOf(order));
        map.put("likes", "0");
        redisTemplate.opsForHash().putAll(key, map);
        // 加入按类型的有序集合用于排序（score 使用 order）
        redisTemplate.opsForZSet().add("files:type:" + type, uuid, order);

        // 广播到 Socket.IO 客户端
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", uuid);
        payload.put("path", fileUrl);
        payload.put("type", type);
        socketServer.getBroadcastOperations().sendEvent("new-image", payload);

        return R.ok(payload);
    }

    // 获取指定类型的所有文件地址，按上传顺序
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam("type") Integer type) {
        if (type == null || (type != TYPE_MOVE && type != TYPE_LIKE)) {
            return R.fail("类型不合法");
        }
        Set<String> ids = redisTemplate.opsForZSet().range("files:type:" + type, 0, -1);
        List<Map<String, Object>> result = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Map<Object, Object> h = redisTemplate.opsForHash().entries("file:" + id);
                if (!h.isEmpty()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", h.get("id"));
                    item.put("path", h.get("path"));
                    item.put("type", Integer.valueOf(h.get("type").toString()));
                    item.put("order", Integer.valueOf(h.get("order").toString()));
                    item.put("likes", Integer.valueOf(h.get("likes").toString()));
                    result.add(item);
                }
            }
        }
        // 已按 ZSet 的 score(order) 顺序
        return R.ok(result);
    }

    // 点赞接口
    @GetMapping("/like")
    public R<Map<String, Object>> like(@RequestParam("id") String id) {
        String key = "file:" + id;
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) {
            return R.fail("文件不存在");
        }
        Long likes = redisTemplate.opsForHash().increment(key, "likes", 1);
        Map<Object, Object> h = redisTemplate.opsForHash().entries(key);
        Map<String, Object> item = new HashMap<>();
        item.put("id", h.get("id"));
        item.put("path", h.get("path"));
        item.put("type", Integer.valueOf(h.get("type").toString()));
        item.put("order", Integer.valueOf(h.get("order").toString()));
        item.put("likes", likes);

        // 通过 Socket.IO 通知点赞变化
        socketServer.getBroadcastOperations().sendEvent("liked", item);

        return R.ok(item);
    }

    // 根据文件id获取文件内容
    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> getFileById(@PathVariable("id") String id) throws IOException {
        String key = "file:" + id;
        Boolean exists = redisTemplate.hasKey(key);
        if (exists == null || !exists) {
            return ResponseEntity.notFound().build();
        }
        Object pathVal = redisTemplate.opsForHash().get(key, "path");
        if (pathVal == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(pathVal.toString());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(path);
        if (contentType == null) contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName().toString() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    // 删除指定类型的所有文件，移动到 uploadPath/delete 下，并清空 Redis 记录
    @DeleteMapping("/delete")
    public R<Map<String, Object>> deleteByType(@RequestParam("type") Integer type) {
        if (type == null || (type != TYPE_MOVE && type != TYPE_LIKE)) {
            return R.fail("类型不合法");
        }
        Set<String> ids = redisTemplate.opsForZSet().range("files:type:" + type, 0, -1);
        int total = ids == null ? 0 : ids.size();
        int moved = 0;
        try {
            Path deleteDir = Paths.get(uploadPath, "delete");
            Files.createDirectories(deleteDir);
            if (ids != null) {
                for (String id : ids) {
                    String key = "file:" + id;
                    Object pathVal = redisTemplate.opsForHash().get(key, "path");
                    if (pathVal != null) {
                        Path src = Paths.get(pathVal.toString());
                        if (Files.exists(src)) {
                            Path dst = deleteDir.resolve(src.getFileName());
                            try {
                                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                                moved++;
                            } catch (Exception moveEx) {
                                // 忽略单个文件移动异常，继续处理
                            }
                        }
                    }
                    // 无论文件是否存在，都清理对应的 hash
                    redisTemplate.delete(key);
                }
            }
            // 清空类型对应的有序集合
            redisTemplate.delete("files:type:" + type);
        } catch (IOException e) {
            return R.fail("删除过程中出现错误: " + e.getMessage());
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("type", type);
        resp.put("total", total);
        resp.put("moved", moved);
        return R.ok(resp);
    }

    // 删除指定类型的所有文件，移动到 uploadPath/delete 下，并清空 Redis 记录
    @DeleteMapping("/deleteById")
    public R<Map<String, Object>> deleteById(@RequestParam("id") String id) {
        redisTemplate.delete("file:" + id);
        return R.ok();
    }


    //清除点赞
    @PostMapping("/clearLikes")
    public R<Void> clearLikes() {
        Set<String> keys = redisTemplate.keys("file:*");
        if (!keys.isEmpty()) {
            for (String key : keys) {
                redisTemplate.opsForHash().put(key, "likes", "0");
            }
        }
        return R.ok();
    }


}
