package com.yizhaoqi.smartpai.memory;

import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {
    private final MemoryStore store;
    private final UserRepository users;
    private final com.yizhaoqi.smartpai.service.ChatHandler chat;
    private final MemoryContextService context;
    private final LegacyMemoryMigration migration;
    public MemoryController(MemoryStore store, UserRepository users,
                            com.yizhaoqi.smartpai.service.ChatHandler chat, MemoryContextService context,
                            LegacyMemoryMigration migration) {
        this.store=store; this.users=users; this.chat=chat; this.context=context; this.migration=migration;
    }
    private long user(Principal principal) {
        if (principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
    private Map<String,Object> result(Object data) { return Map.of("code",200,"message","success","data",data); }
    @GetMapping("/sessions/current")
    public Map<String,Object> current(Principal p) {
        long uid=user(p);
        migration.migrateUser(uid,p.getName());
        String id=store.current(uid,false); context.cacheCurrent(uid,id);
        return result(Map.of("conversationId",id));
    }
    @PostMapping("/sessions")
    public Map<String,Object> create(Principal p) {
        long uid=user(p); String id=store.current(uid,true); chat.cancelClosedMemoryRuns(uid);
        context.cacheCurrent(uid,id);
        return result(Map.of("conversationId",id));
    }
    @PostMapping("/sessions/{id}/resume")
    public Map<String,Object> resume(Principal p, @PathVariable String id) {
        long uid=user(p); store.resume(uid,id); chat.cancelClosedMemoryRuns(uid); context.cacheCurrent(uid,id);
        return result(Map.of("conversationId",id));
    }
    @DeleteMapping("/sessions/{id}")
    public Map<String,Object> delete(Principal p, @PathVariable String id) {
        long uid=user(p); store.deleteSession(uid,id); context.forget(uid,id); chat.cancelClosedMemoryRuns(uid);
        return result(Map.of("deleted",true));
    }
    @GetMapping("/sessions")
    public Map<String,Object> list(Principal p) { return result(store.sessions(user(p))); }
    @GetMapping("/sessions/{id}/messages")
    public Map<String,Object> history(Principal p, @PathVariable String id, @RequestParam(defaultValue="9223372036854775807") long before) {
        List<Map<String,Object>> messages = new ArrayList<>();
        for (var t : store.historyBefore(user(p),id,before)) {
            messages.add(Map.of("role","user","content",t.question(),"timestamp",t.createdAt().toString(),"turnId",t.id()));
            messages.add(Map.of("role","assistant","content",t.answer(),"timestamp",t.createdAt().toString(),"turnId",t.id(),"status","finished"));
        }
        return result(messages);
    }
    @GetMapping("/documents/{type}/versions")
    public Map<String,Object> versions(Principal principal,@PathVariable String type,
                                      @RequestParam(defaultValue="9223372036854775807") long before) {
        return result(store.versions(user(principal),memoryType(type),before));
    }
    @GetMapping("/documents/{type}/versions/{version}")
    public Map<String,Object> version(Principal principal,@PathVariable String type,@PathVariable long version) {
        return result(store.version(user(principal),memoryType(type),version));
    }
    public record RollbackRequest(long targetVersion,long expectedVersion,String reason) {}
    @PostMapping("/documents/{type}/rollback")
    public Map<String,Object> rollback(Principal principal,@PathVariable String type,@RequestBody RollbackRequest request) {
        if(request.targetVersion()<1 || request.expectedVersion()<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效版本");
        try { return result(Map.of("version",store.rollback(user(principal),memoryType(type),request.targetVersion(),request.expectedVersion(),request.reason()))); }
        catch(MemoryTypes.MemoryVersionConflict conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT,"记忆版本已变更，请重新读取"); }
        catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage()); }
    }
    private MemoryTypes.MemoryType memoryType(String type) {
        try { return com.yizhaoqi.smartpai.agent.tool.PersonalMemoryTool.documentType(type); }
        catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,invalid.getMessage()); }
    }
    @GetMapping("/documents")
    public Map<String,Object> documents(Principal p) {
        long uid = user(p);
        return result(store.documents(uid).stream().map(d -> Map.of("fileName",d.type().fileName(),"type",d.type(),"content",d.content(),"version",d.version(),"sections",d.sections())).toList());
    }
}
