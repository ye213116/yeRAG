package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPolicyTest {
    MemoryPolicy policy=new MemoryPolicy(new MemoryProperties());
    Turn turn(String question) { return new Turn(2,1,"s","r",question,"answer","COMPLETED",Instant.now()); }
    MemoryPatch patch(Turn turn) { return new MemoryPatch(MemoryType.PREFERENCE,null,"解释偏好",MemoryPatchOperation.UPSERT_SECTION,"Java示例优先。","技术解释示例的选择范围。",MemoryDecision.CREATE,"持续偏好",0L,List.of(new MemorySource(turn.id(),turn.question()))); }
    @Test void quotedCodeAndRecentlyAreSemanticDecisionsInsteadOfBatchBlacklist() {
        for(String question:List.of("我最近开始主要用Java","以后说明先讲业务，示例用‘Java’","我的偏好是解释代码：```java\nint i=1;\n```")) {
            Turn current=turn(question);var patch=patch(current);
            assertEquals(List.of(patch),policy.validatePatches(List.of(patch),List.of(current),current,List.of()));
        }
    }
    @Test void provenanceRequiresCurrentUserAndExactQuoteFromProvidedOwner() {
        Turn current=turn("示例优先用Java");var valid=patch(current);var foreign=new Turn(3,2,"other","r","我是工程师","answer","COMPLETED",Instant.now());
        assertThrows(IllegalArgumentException.class,()->policy.validateSources(List.of(new MemorySource(3,"我是工程师")),List.of(foreign,current),current));
        assertThrows(IllegalArgumentException.class,()->policy.validateSources(List.of(new MemorySource(2,"我是工程师")),List.of(current),current));
        assertThrows(IllegalArgumentException.class,()->policy.validateSources(List.of(new MemorySource(2,"assistant","answer")),List.of(current),current));
        assertEquals(List.of(valid),policy.validatePatches(List.of(valid),List.of(current),current,List.of()));
    }
    @Test void sectionOwnershipVersionAndExplicitDeletionAreRequired() {
        Turn current=turn("请忘记我的职业");UUID id=UUID.randomUUID();
        var old=new SectionMetadata(id,1,"职业","职业背景范围。",3,1,false);
        var doc=new MemoryDocument(1,1,MemoryType.PROFILE,"# Profile Memory\n\n## 职业\n\n工程师\n",List.of(old),3);
        var delete=new MemoryPatch(MemoryType.PROFILE,id,"职业",MemoryPatchOperation.DELETE_SECTION,null,"职业背景范围。",MemoryDecision.DELETE,"明确删除",3L,List.of(new MemorySource(2,current.question())));
        assertEquals(List.of(delete),policy.validatePatches(List.of(delete),List.of(current),current,List.of(doc)));
        var stale=new MemoryPatch(delete.target(),id,delete.heading(),delete.operation(),null,delete.description(),delete.decision(),delete.reason(),2L,delete.sources());
        assertThrows(MemoryVersionConflict.class,()->policy.validatePatches(List.of(stale),List.of(current),current,List.of(doc)));
        assertThrows(IllegalArgumentException.class,()->policy.validatePatches(List.of(delete,delete),List.of(current),current,List.of(doc)));
        assertThrows(IllegalArgumentException.class,()->policy.validatePatches(List.of(delete),List.of(current),current,List.of()));
    }
    @Test void byteBudgetNeverSplitsUnicode() {
        assertEquals("你",MemoryContextService.clip("你好",4));assertEquals("",MemoryContextService.clip("😀",3));assertEquals("😀",MemoryContextService.clip("😀a",4));
    }
}
