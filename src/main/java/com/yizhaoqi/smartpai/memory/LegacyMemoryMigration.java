package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Imports live Redis data from the retired chat path. Repeated scans are safe during a rolling deployment. */
@Component
@Order(2)
public class LegacyMemoryMigration implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyMemoryMigration.class);
    private static final TypeReference<List<Map<String,String>>> HISTORY = new TypeReference<>() {};
    private final RedisTemplate<String,Object> legacyRedis;
    private final StringRedisTemplate redis;
    private final MemoryStore store;
    private final MemoryContextService context;
    private final UserRepository users;
    private final ObjectMapper json;

    public LegacyMemoryMigration(RedisTemplate<String,Object> legacyRedis, StringRedisTemplate redis,
                                 MemoryStore store, MemoryContextService context, UserRepository users, ObjectMapper json) {
        this.legacyRedis=legacyRedis; this.redis=redis; this.store=store;
        this.context=context; this.users=users; this.json=json;
    }
    @Override public void run(ApplicationArguments args) { migratePending(); }

    @Scheduled(fixedDelayString = "${ai.memory.legacy-migration-delay-ms:3600000}")
    public synchronized void migratePending() {
        try {
            scan("user:*:current_conversation", this::migratePointer);
            scan("memory:v2:session:*:context", this::migrateSummary);
        } catch (Exception unavailable) {
            LOG.warn("Legacy memory migration deferred: {}",unavailable.getClass().getSimpleName());
        }
    }

    public void migrateUser(long user, String username) {
        try {
            migratePointer("user:"+username+":current_conversation",user);
            String numeric = "user:"+user+":current_conversation";
            if (!numeric.equals("user:"+username+":current_conversation") && users.findByUsername(Long.toString(user)).isEmpty())
                migratePointer(numeric,user);
        } catch (Exception unavailable) { LOG.warn("Legacy user migration deferred user={}",user); }
    }

    private void scan(String pattern, java.util.function.Consumer<String> consumer) {
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            while (keys.hasNext()) {
                String key=keys.next();
                try { consumer.accept(key); }
                catch (Exception invalid) { LOG.warn("Legacy memory key skipped: {}",key); }
            }
        }
    }
    private void migratePointer(String key) {
        String alias=key.substring("user:".length(),key.length()-":current_conversation".length());
        Optional<User> byName=users.findByUsername(alias);
        Optional<User> byId=Optional.empty();
        if (alias.matches("[0-9]+")) {
            try { byId=users.findById(Long.parseLong(alias)); }
            catch (NumberFormatException ignored) { return; }
        }
        if (byName.isPresent() && byId.isPresent() && !byName.get().getId().equals(byId.get().getId())) return;
        User owner=byName.isPresent() ? byName.get() : byId.orElse(null);
        if (owner!=null) {
            String canonical="user:"+owner.getUsername()+":current_conversation";
            if (!key.equals(canonical) && Boolean.TRUE.equals(legacyRedis.hasKey(canonical))) return;
            migratePointer(key,owner.getId());
        }
    }
    private void migratePointer(String key, long user) {
        Object idValue=legacyRedis.opsForValue().get(key);
        if (!(idValue instanceof String id)) return;
        try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { return; }
        String historyKey="conversation:"+id;
        Long pointerTtl=legacyRedis.getExpire(key,TimeUnit.MILLISECONDS);
        Long historyTtl=legacyRedis.getExpire(historyKey,TimeUnit.MILLISECONDS);
        if (pointerTtl==null || historyTtl==null || pointerTtl<=0 || historyTtl<=0) return;
        Object raw=legacyRedis.opsForValue().get(historyKey);
        if (!(raw instanceof String encoded) || encoded.length()>1_000_000) return;
        try {
            List<Map<String,String>> messages=json.readValue(encoded,HISTORY);
            List<MemoryStore.LegacyTurn> turns=parse(messages);
            if (turns.isEmpty()) return;
            Instant expires=Instant.now().plusMillis(Math.min(pointerTtl,historyTtl));
            if (store.importLegacySession(user,id,turns,expires)) LOG.info("Migrated legacy conversation session={}",id);
        } catch (Exception invalid) { LOG.warn("Legacy conversation migration deferred for session={}",id); }
    }
    private List<MemoryStore.LegacyTurn> parse(List<Map<String,String>> messages) {
        if (messages==null) return List.of();
        int start=Math.max(0,messages.size()-20);
        if ((start&1)!=0) start++;
        List<MemoryStore.LegacyTurn> turns=new ArrayList<>();
        Instant now=Instant.now();
        for (int i=start; i+1<messages.size(); i+=2) {
            Map<String,String> question=messages.get(i),answer=messages.get(i+1);
            if (question==null || answer==null || !"user".equals(question.get("role"))
                    || !"assistant".equals(answer.get("role")) || question.get("content")==null
                    || answer.get("content")==null) continue;
            try {
                Instant created=LocalDateTime.parse(question.get("timestamp"))
                        .atZone(ZoneId.systemDefault()).toInstant();
                if (created.isAfter(now)) created=now;
                turns.add(new MemoryStore.LegacyTurn(question.get("content"),answer.get("content"),created));
            } catch (Exception invalid) { /* Skip an incomplete or malformed pair. */ }
        }
        return turns;
    }
    private void migrateSummary(String key) {
        String prefix="memory:v2:session:",suffix=":context";
        String id=key.substring(prefix.length(),key.length()-suffix.length());
        try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { return; }
        Object owner=redis.opsForHash().get(key,"owner_user_id");
        if (owner==null) return;
        long user=Long.parseLong(owner.toString());
        context.migrateLegacySummary(user,id);
    }
}
