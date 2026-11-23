package com.yizhaoqi.smartpai.coding;

import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.task.*;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/coding")
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class CodingController {
    private final CodingTaskService tasks;
    private final CodingTaskStore store;
    private final CodingProperties properties;
    private final UserRepository users;
    public CodingController(CodingTaskService tasks,CodingTaskStore store,CodingProperties properties,UserRepository users) {
        this.tasks=tasks;this.store=store;this.properties=properties;this.users=users;
    }
    private long user(Principal p) {
        if(p==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByUsername(p.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
    private Map<String,Object> result(Object data) { return Map.of("code",200,"message","success","data",data); }
    @GetMapping("/repositories") public Map<String,Object> repositories(Principal p) {
        tasks.enabled();long owner=user(p);var rows=new ArrayList<Map<String,Object>>();
        properties.getRepositories().forEach((id,r) -> {
            if(r.getAllowedUsers().contains(owner)) rows.add(Map.of("id",id,"name",r.getFullName(),"branches",r.getAllowedBranches()));
        });return result(rows);
    }
    public record CreateWorkspace(String repositoryId,String branch,Set<WorkspacePermission> permissions) {}
    public record Permissions(Set<WorkspacePermission> permissions) {}
    @GetMapping("/workspaces") public Map<String,Object> workspaces(Principal p) {
        long owner=user(p);return result(store.workspaces(owner).stream().filter(w -> {
            try { properties.repository(w.repositoryId(),owner);return true; } catch(ResponseStatusException denied) { return false; }
        }).toList());
    }
    @PostMapping("/workspaces") public Map<String,Object> create(Principal p,@RequestBody CreateWorkspace request) {
        return result(tasks.create(user(p),request.repositoryId(),request.branch(),request.permissions()));
    }
    @PatchMapping("/workspaces/{id}/permissions") public Map<String,Object> permissions(Principal p,@PathVariable String id,@RequestBody Permissions request) {
        tasks.permissions(user(p),id,request.permissions());return result(Map.of("updated",true));
    }
    @GetMapping("/tasks") public Map<String,Object> list(Principal p) { long owner=user(p);return result(store.list(owner).stream().filter(row -> {
        try { properties.repository(store.workspace(owner,(String)row.get("workspace_id")).repositoryId(),owner);return true; }
        catch(ResponseStatusException denied) { return false; }
    }).toList()); }
    @GetMapping("/tasks/{id}") public Map<String,Object> read(Principal p,@PathVariable String id) { return result(tasks.view(user(p),id)); }
    @GetMapping("/tasks/{id}/diff") public Map<String,Object> diff(Principal p,@PathVariable String id) {
        Object snapshot=tasks.view(user(p),id).get("result");
        return result(snapshot==null?Map.of():snapshot);
    }
    @GetMapping("/tasks/{id}/events/{eventId}") public Map<String,Object> event(Principal p,@PathVariable String id,@PathVariable long eventId) {
        tasks.view(user(p),id);return result(store.eventDetails(id,eventId));
    }
    @PostMapping("/tasks/{id}/cancel") public Map<String,Object> cancel(Principal p,@PathVariable String id) {
        tasks.cancel(user(p),id);return result(Map.of("requested",true));
    }
    @ExceptionHandler({IllegalArgumentException.class,SecurityException.class,IllegalStateException.class})
    public ResponseEntity<?> failure(RuntimeException error) {
        int code=error instanceof SecurityException?403:error instanceof IllegalArgumentException?400:503;
        return ResponseEntity.status(code).body(Map.of("code",code,"message",code==503?"Coding 执行环境暂不可用":code==403?"Coding 权限不足":"Coding 请求参数无效"));
    }
}
