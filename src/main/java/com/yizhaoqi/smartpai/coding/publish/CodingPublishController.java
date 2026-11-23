package com.yizhaoqi.smartpai.coding.publish;

import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/coding/tasks")
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingPublishController {
    private final GitHubPublishService publisher;
    private final UserRepository users;
    public CodingPublishController(GitHubPublishService publisher,UserRepository users) { this.publisher=publisher;this.users=users; }
    @PostMapping("/{id}/pull-request")
    public Map<String,Object> publish(Principal principal,@PathVariable String id,@RequestBody GitHubPublishService.Confirmation confirmation) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long owner=users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        return Map.of("code",200,"message","success","data",Map.of("url",publisher.publish(owner,id,confirmation)));
    }
    @ExceptionHandler({SecurityException.class,IllegalStateException.class,IllegalArgumentException.class})
    public ResponseEntity<?> failure(RuntimeException error) {
        int code=error instanceof SecurityException?403:error instanceof IllegalArgumentException?400:503;
        return ResponseEntity.status(code).body(Map.of("code",code,"message",code==503?"GitHub 发布暂不可用，成果已保留":"发布请求无效或权限不足"));
    }
}
