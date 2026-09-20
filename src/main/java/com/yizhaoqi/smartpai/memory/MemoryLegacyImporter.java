package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Reads only the authenticated account's exact legacy username key. Never scans Redis. */
@Component
public class MemoryLegacyImporter {
    private final MemoryStore store;
    private final UserRepository users;
    private final RedisTemplate<String,Object> legacy;
    private final ObjectMapper json;
    public MemoryLegacyImporter(MemoryStore store, UserRepository users,
            @Qualifier("redisTemplate") RedisTemplate<String,Object> legacy, ObjectMapper json) {
        this.store=store; this.users=users; this.legacy=legacy; this.json=json;
    }
    public void importIfNeeded(long user) {
        if (!store.sessions(user).isEmpty()) return;
        var account=users.findById(user).orElseThrow();
        try {
            Object id=legacy.opsForValue().get("user:"+account.getUsername()+":current_conversation");
            if (!(id instanceof String sid)) return;
            Object raw=legacy.opsForValue().get("conversation:"+sid);
            if (!(raw instanceof String source)) return;
            if (MemoryContextService.bytes(source)>1024*1024) return;
            List<Map<String,String>> history=json.readValue(source,new TypeReference<>(){});
            Long remaining=legacy.getExpire("conversation:"+sid,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(remaining!=null && remaining==-2) return;
            store.importLegacy(user,sid,history,remaining!=null && remaining>0 ? java.time.Duration.ofMillis(remaining) : null);
        } catch (Exception unavailable) {
            // Legacy cache loss must not prevent creating a fresh, authenticated session.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Legacy chat import skipped user={} errorType={}",user,unavailable.getClass().getSimpleName());
        }
    }
}
