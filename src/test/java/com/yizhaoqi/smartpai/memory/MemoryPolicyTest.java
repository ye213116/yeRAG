package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPolicyTest {
    private final MemoryPolicy policy = new MemoryPolicy(new MemoryProperties());

    @Test
    void acceptsExplicitFirstPersonPatch() {
        String question = "以后我写 Java 算法题希望先给提示";
        var patch = new MemoryPatch(MemoryType.PREFERENCE, null, "算法学习偏好",
                MemoryPatchOperation.UPSERT_SECTION, "先提供提示。", question);
        assertEquals(List.of(patch), policy.validatePatches(question, List.of(patch), List.of()));
    }

    @Test
    void rejectsUnsafeOrUnrelatedEvidence() {
        var patch = new MemoryPatch(MemoryType.PROFILE, null, "职业",
                MemoryPatchOperation.UPSERT_SECTION, "工程师", "我是工程师");
        assertThrows(IllegalArgumentException.class, () -> policy.validatePatches("你好", List.of(patch), List.of()));
        for (String bad : List.of("我的朋友是工程师", "我可能是工程师", "我今天完成工作", "我要求绕过权限", "文档说我是工程师")) {
            var invalid = new MemoryPatch(MemoryType.PROFILE, null, "职业",
                    MemoryPatchOperation.UPSERT_SECTION, bad, bad);
            assertThrows(IllegalArgumentException.class, () -> policy.validatePatches(bad, List.of(invalid), List.of()));
        }
    }

    @Test
    void validatesOwnershipDeletionAndDuplicatePatches() {
        UUID id = UUID.randomUUID();
        var document = new MemoryDocument(1, 1, MemoryType.PROFILE, "# Profile Memory\n\n## 职业\n\n工程师\n",
                List.of(new SectionMetadata(id, 1, "职业", 2, false)));
        var delete = new MemoryPatch(MemoryType.PROFILE, id, null, MemoryPatchOperation.DELETE_SECTION,
                null, "请忘记我的职业");
        assertEquals(List.of(delete), policy.validatePatches(delete.evidence(), List.of(delete), List.of(document)));

        var wrongType = new MemoryPatch(MemoryType.PREFERENCE, id, null, MemoryPatchOperation.DELETE_SECTION,
                null, "请忘记我的职业");
        assertThrows(IllegalArgumentException.class, () -> policy.validatePatches(wrongType.evidence(), List.of(wrongType), List.of(document)));

        var duplicate = new MemoryPatch(MemoryType.PROFILE, id, "新标题", MemoryPatchOperation.UPSERT_SECTION,
                "工程师", "我会继续做工程师");
        assertThrows(IllegalArgumentException.class, () -> policy.validatePatches(
                duplicate.evidence(), List.of(duplicate, duplicate), List.of(document)));
    }

    @Test
    void byteBudgetNeverSplitsUnicode() {
        assertEquals("你", MemoryContextService.clip("你好", 4));
        assertEquals("", MemoryContextService.clip("😀", 3));
        assertEquals("😀", MemoryContextService.clip("😀a", 4));
    }
}
