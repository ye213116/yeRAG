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
    @GetMapping("/sessions")
    public Map<String,Object> list(Principal p) { return result(store.sessions(user(p))); }
    @GetMapping("/sessions/{id}/messages")
    public Map<String,Object> history(Principal p, @PathVariable String id) {
        List<Map<String,Object>> messages = new ArrayList<>();
        for (var t : store.history(user(p),id)) {
            messages.add(Map.of("role","user","content",t.question(),"timestamp",t.createdAt().toString()));
            messages.add(Map.of("role","assistant","content",t.answer(),"timestamp",t.createdAt().toString(),"status","finished"));
        }
        return result(messages);
    }
    @GetMapping("/documents")
    public Map<String,Object> documents(Principal p) {
        long uid = user(p);
        return result(store.documents(uid).stream().map(d -> Map.of("fileName",d.type().fileName(),"type",d.type(),"content",d.content())).toList());
    }
}
