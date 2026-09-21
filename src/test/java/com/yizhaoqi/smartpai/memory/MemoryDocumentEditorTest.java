package com.yizhaoqi.smartpai.memory;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDocumentEditorTest {
    final MemoryDocumentEditor editor = new MemoryDocumentEditor();
    final UUID id = UUID.randomUUID();
    SectionMetadata metadata(String heading, boolean deleted) { return new SectionMetadata(id, 1, heading, 0, deleted); }
    ResolvedMemoryPatch upsert(String heading, String body) { return new ResolvedMemoryPatch(id,heading,MemoryPatchOperation.UPSERT_SECTION,body); }

    @Test void createsRenamesAndDeletesWholeSection() {
        var created=editor.apply("# Profile Memory\n",List.of(upsert("职业","工程师")),Map.of());
        assertEquals("# Profile Memory\n\n## 职业\n\n工程师\n",created.content());
        var renamed=editor.apply(created.content(),List.of(upsert("背景","Java 工程师")),Map.of(id,metadata("职业",false)));
        assertTrue(renamed.content().contains("## 背景"));
        assertFalse(renamed.content().contains(id.toString()));
        var deleted=editor.apply(renamed.content(),List.of(new ResolvedMemoryPatch(id,null,MemoryPatchOperation.DELETE_SECTION,null)),Map.of(id,metadata("背景",false)));
        assertEquals("# Profile Memory\n",deleted.content());
    }
    @Test void distinguishesFencedCodeAndSubheadings() {
        var parsed=editor.parse("# Profile Memory\n\n## 技能\n\n### 语言\n\n```md\n## example\n```\n\n熟悉 Java。\n");
        assertEquals(1,parsed.sections().size());
        assertEquals("熟悉 Java。",editor.firstParagraph(parsed.sections().get(0).content()));
        assertEquals("",editor.firstParagraph("### 标题\n~~~\n代码\n~~~"));
    }
    @Test void rejectsDamagedDocumentsDuplicateHeadingsAndMissingSections() {
        for(String bad:List.of("## 孤立\n正文", "# 标题\n前言", "# 标题\n## A\n甲\n## A\n乙", "# 标题\n## A\n```\n未闭合", "# 标题\n# 第二标题"))
            assertThrows(IllegalArgumentException.class,()->editor.parse(bad));
        assertThrows(IllegalArgumentException.class,()->editor.apply("# Profile Memory\n",List.of(upsert("新名","正文")),Map.of(id,metadata("缺失",false))));
        assertThrows(IllegalArgumentException.class,()->editor.apply("# Profile Memory\n",List.of(upsert("A","## 非法 section")),Map.of()));
    }
    @Test void appliesRenamesAgainstOriginalIdentitiesAndAllowsFinalHeadingSwap() {
        UUID second=UUID.randomUUID();
        var result=editor.apply("# Profile Memory\n\n## A\n\nfirst\n\n## B\n\nsecond\n",
                List.of(upsert("B","new first"),new ResolvedMemoryPatch(second,"A",MemoryPatchOperation.UPSERT_SECTION,"new second")),
                Map.of(id,metadata("A",false),second,new SectionMetadata(second,1,"B",0,false)));
        assertEquals(List.of("B","A"),result.sections().stream().map(MemoryDocumentEditor.ParsedSection::heading).toList());
        assertEquals("new first",result.sections().get(0).content());
    }
    @Test void handlesLongFencesAndSkipsNonParagraphDescriptionBlocks() {
        String body="````md\n```\n## example\n````\n\n> quote\n\n- bullet\n\nReal   paragraph\ncontinued\n\nOther paragraph";
        assertEquals(1,editor.parse("# Profile Memory\n## A\n"+body).sections().size());
        assertEquals("Real paragraph continued",editor.firstParagraph(body));
    }
    @Test void restoresExplicitTombstoneAndRejectsDuplicatePatchOrHeading() {
        var restored=editor.apply("# Profile Memory\n",List.of(upsert("职业","工程师")),Map.of(id,metadata("职业",true)));
        assertEquals(1,restored.sections().size());
        assertThrows(IllegalArgumentException.class,()->editor.apply(restored.content(),List.of(upsert("职业","其他")),Map.of()));
        assertThrows(IllegalArgumentException.class,()->editor.apply("# Profile Memory\n",List.of(upsert("A","1"),upsert("B","2")),Map.of()));
    }

    @Test void headingSwapWithEqualBodiesUsesIdentityNotRecordEquality() {
        UUID second=UUID.randomUUID();
        var result=editor.apply("# Profile Memory\n\n## A\n\nsame\n\n## B\n\nsame\n",
                List.of(upsert("B","same"),new ResolvedMemoryPatch(second,"A",MemoryPatchOperation.UPSERT_SECTION,"same")),
                Map.of(id,metadata("A",false),second,new SectionMetadata(second,1,"B",0,false)));
        assertEquals(List.of("B","A"),result.sections().stream().map(MemoryDocumentEditor.ParsedSection::heading).toList());
    }
}
