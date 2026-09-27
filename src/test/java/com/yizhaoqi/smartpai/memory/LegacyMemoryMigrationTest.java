package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyMemoryMigrationTest {
    @SuppressWarnings("unchecked")
    @Test void importsOnlyLiveCompletePairsForTheResolvedUser() {
        RedisTemplate<String,Object> redis=mock(RedisTemplate.class);
        ValueOperations<String,Object> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String id=UUID.randomUUID().toString();
        when(values.get("user:alice:current_conversation")).thenReturn(id);
        when(values.get("conversation:"+id)).thenReturn("""
            [{"role":"user","content":"Q","timestamp":"2026-09-27T10:00:00"},
             {"role":"assistant","content":"A","timestamp":"2026-09-27T10:00:01"}]
            """);
        when(redis.getExpire("user:alice:current_conversation",TimeUnit.MILLISECONDS)).thenReturn(5000L);
        when(redis.getExpire("conversation:"+id,TimeUnit.MILLISECONDS)).thenReturn(5000L);
        UserRepository users=mock(UserRepository.class);
        when(users.findByUsername("1")).thenReturn(Optional.empty());
        MemoryStore store=mock(MemoryStore.class);
        LegacyMemoryMigration migration=new LegacyMemoryMigration(redis,mock(StringRedisTemplate.class),store,
                mock(MemoryContextService.class),users,new ObjectMapper());

        migration.migrateUser(1,"alice");

        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<MemoryStore.LegacyTurn>> turns=
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(store).importLegacySession(eq(1L),eq(id),turns.capture(),any(Instant.class));
        assertEquals(1,turns.getValue().size());
        assertEquals("Q",turns.getValue().get(0).question());
        assertEquals("A",turns.getValue().get(0).answer());
    }
}
