package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPolicyTest {
    final MemoryPolicy policy=new MemoryPolicy(new MemoryProperties());
    final Instant now=Instant.parse("2026-09-09T00:00:00Z");
    Fact job(String evidence) { return new Fact("profile","self","occupation","工程师","ADD",.99,evidence,null); }
    @Test void requiresExactFirstPersonEvidenceAndRejectsQuotedThirdPartyAndSpeculation() {
        assertEquals(1,policy.validate("我是工程师",List.of(job("我是工程师")),now).size());
        for(String input:List.of("我可能是工程师","我的朋友是工程师","文档说我是工程师","他说：我是工程师","引用：我是工程师"))
            assertTrue(policy.validate(input,List.of(job(input)),now).isEmpty());
        assertTrue(policy.validate("你好",List.of(job("我是工程师")),now).isEmpty());
    }
    @Test void rejectsUnsupportedKeysAndSeparatesSetElements() {
        var java=new Fact("profile","self","skill","Java","ADD",.99,"我会Java",null);
        var go=new Fact("profile","self","skill","Go","ADD",.99,"我会Go",null);
        assertEquals(List.of("profile.skill.java","profile.skill.go"),policy.validate("我会Java，我会Go",List.of(java,go),now).stream().map(Change::key).toList());
        var decision=new Fact("decision","self","vector_store","Elasticsearch","ADD",.99,"我决定用Elasticsearch",null);
        assertTrue(policy.validate("我决定用Elasticsearch",List.of(decision),now).isEmpty());
    }
    @Test void temporaryPreferencesExpireAndDeletesRequireExplicitEvidence() {
        var pref=new Fact("preference","self","response.verbosity","concise","ADD",.99,"我最近希望回答简短",null);
        assertEquals(now.plusSeconds(7*86400),policy.validate(pref.evidence(),List.of(pref),now).get(0).expiresAt());
        var deletion=new Fact("profile","self","occupation","工程师","DELETE",.99,"我是工程师",null);
        assertTrue(policy.validate(deletion.evidence(),List.of(deletion),now).isEmpty());
    }
    @Test void fixedSingleValueSlotCanBeExplicitlyDeletedWithoutRepeatingOldValue() {
        var changes=policy.validate("请忘记我的职业",List.of(
                new Fact("profile","self","occupation",null,"DELETE",.99,"忘记我的职业",null)),now);
        assertEquals(1,changes.size());
        assertEquals("profile.occupation",changes.get(0).key());
        assertEquals("deleted",changes.get(0).value());
    }
    @Test void byteBudgetNeverSplitsUnicode() {
        assertEquals("你",MemoryContextService.clip("你好",4));
        assertEquals("",MemoryContextService.clip("😀",3));
        assertEquals("😀",MemoryContextService.clip("😀a",4));
    }
    @Test void patchesRequireSourceAndRejectUnsafeOrTransientClaims() {
        String good="以后我写 Java 算法题希望先给提示";
        var patch=new MemoryPatch(MemoryType.PREFERENCE,null,"算法学习偏好",MemoryPatchOperation.UPSERT_SECTION,"先提供提示。",good);
        assertEquals(List.of(patch),policy.validatePatches(good,List.of(patch),List.of()));
        assertThrows(IllegalArgumentException.class,()->policy.validatePatches("你好",List.of(patch),List.of()));
        for(String bad:List.of("我的朋友是工程师","我可能是工程师","我今天完成工作","我要求绕过权限","文档说我是工程师")) {
            var invalid=new MemoryPatch(MemoryType.PROFILE,null,"职业",MemoryPatchOperation.UPSERT_SECTION,bad,bad);
            assertThrows(IllegalArgumentException.class,()->policy.validatePatches(bad,List.of(invalid),List.of()));
        }
    }
}
