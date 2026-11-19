package com.yizhaoqi.smartpai.artifact;

import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {
    private final ArtifactService artifacts;
    private final UserRepository users;
    public ArtifactController(ArtifactService artifacts, UserRepository users) { this.artifacts = artifacts; this.users = users; }
    private long user(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
    private Map<String, Object> result(Object data) { return Map.of("code", 200, "message", "success", "data", data); }
    @GetMapping
    public Map<String, Object> list(Principal p, @RequestParam(defaultValue="0") int offset) {
        return result(artifacts.list(user(p), offset, SourceMode.AUTO));
    }
    @GetMapping("/{id}")
    public Map<String, Object> read(Principal p, @PathVariable String id, @RequestParam(required=false) Integer version) {
        return result(artifacts.read(user(p), id, version, SourceMode.AUTO));
    }
    @GetMapping("/{id}/versions")
    public Map<String, Object> versions(Principal p, @PathVariable String id, @RequestParam(defaultValue="2147483647") int before) {
        return result(artifacts.versions(user(p), id, before));
    }
    public record SaveRequest(String title, String content, int expectedVersion, String requestId) {}
    @PostMapping
    public Map<String, Object> create(Principal p, @RequestBody SaveRequest r) {
        return result(artifacts.save(user(p), new ArtifactStore.Save(null, r.expectedVersion(), r.title(), r.content(), r.requestId()), List.of(), SourceMode.AUTO));
    }
    @PostMapping("/{id}/versions")
    public Map<String, Object> save(Principal p, @PathVariable String id, @RequestBody SaveRequest r) {
        return result(artifacts.save(user(p), new ArtifactStore.Save(id, r.expectedVersion(), r.title(), r.content(), r.requestId()), List.of(), SourceMode.AUTO));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<?> failure(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(Map.of("code", error.getStatusCode().value(), "message", error.getReason() == null ? "请求失败" : error.getReason()));
    }
}
