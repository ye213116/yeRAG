package com.yizhaoqi.smartpai.memory;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** The model proposes document patches; the server owns identity, evidence and lifecycle rules. */
@Component
public class MemoryPolicy {
    private final MemoryProperties config;
    private static final Pattern UNSAFE = Pattern.compile(
            "可能|也许|假如|假设|如果|朋友|同事|他说|她说|引用|文档说|工具返回|检索结果|今天|明天|本周|这周|暂时|最近|截止|会议|进度|待办|deadline|todo|ignore.{0,30}(previous|instruction)|system prompt|developer instruction|绕过|越权|覆盖.{0,12}(系统|提示)|提升.{0,12}权限|自动执行.{0,12}(删除|命令|危险)",
            Pattern.CASE_INSENSITIVE);

    public MemoryPolicy(MemoryProperties config) {
        this.config = config;
    }

    /** Invalid batches fail as a whole; the store repeats ownership checks under locks. */
    public List<MemoryPatch> validatePatches(String question, List<MemoryPatch> patches,
                                             List<MemoryDocument> documents) {
        if (patches == null || patches.size() > config.getMaxPatches())
            throw new IllegalArgumentException("invalid patch count");
        if (patches.isEmpty()) return List.of();
        if (question == null || question.matches("(?s).*['“”\"].*") || question.contains("```")
                || UNSAFE.matcher(question).find()) throw new IllegalArgumentException("unsafe source");

        Map<UUID, MemoryDocument> owners = new HashMap<>();
        for (MemoryDocument document : documents) {
            for (SectionMetadata section : document.sections()) owners.put(section.id(), document);
        }

        Set<UUID> seen = new HashSet<>();
        Set<String> newHeadings = new HashSet<>();
        for (MemoryPatch patch : patches) {
            if (patch == null || patch.target() == null || patch.operation() == null
                    || patch.evidence() == null || patch.evidence().isBlank()
                    || !question.contains(patch.evidence())
                    || !Pattern.compile("我|以后回答|以后回复|(?i)\\b(I|my|me)\\b")
                    .matcher(patch.evidence()).find()
                    || UNSAFE.matcher(patch.evidence()).find())
                throw new IllegalArgumentException("invalid evidence");

            if (patch.sectionId() != null) {
                MemoryDocument owner = owners.get(patch.sectionId());
                if (owner == null || owner.type() != patch.target() || !seen.add(patch.sectionId()))
                    throw new IllegalArgumentException("invalid section ownership or conflict");
            }

            if (patch.operation() == MemoryPatchOperation.DELETE_SECTION) {
                if (patch.sectionId() == null || patch.content() != null
                        || !patch.evidence().matches("(?is).*(忘记|删除|不再|forget|delete|remove).*"))
                    throw new IllegalArgumentException("invalid deletion");
            } else {
                String heading = patch.heading();
                if (heading == null || heading.isBlank() || heading.contains("\n") || heading.contains("\r")
                        || heading.codePointCount(0, heading.length()) > config.getMaxHeadingCodePoints()
                        || patch.content() == null || patch.content().isBlank()
                        || MemoryContextService.bytes(patch.content()) > config.getMaxSectionBytes()
                        || UNSAFE.matcher(heading + "\n" + patch.content()).find())
                    throw new IllegalArgumentException("invalid section content");
                if (patch.sectionId() == null && !newHeadings.add(patch.target() + ":" + heading.strip()))
                    throw new IllegalArgumentException("duplicate new heading");
            }
        }
        return List.copyOf(patches);
    }
}
