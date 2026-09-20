package com.yizhaoqi.smartpai.memory;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** The model proposes facts; the server owns identity, keys, evidence and lifecycle rules. */
@Component
public class MemoryPolicy {
    private final MemoryProperties config;
    private static final Pattern UNSAFE = Pattern.compile("可能|也许|假如|假设|如果|朋友|同事|他说|她说|引用|文档|工具返回|检索结果|```|[“”\"]|ignore previous", Pattern.CASE_INSENSITIVE);
    private static final Map<String,String> SKILLS = Map.of("java","java", "go","go", "golang","go", "python","python", "javascript","javascript", "typescript","typescript", "rust","rust", "kotlin","kotlin");
    public MemoryPolicy(MemoryProperties config) { this.config = config; }
    public List<Change> validate(String input, List<Fact> candidates, Instant now) {
        if (input == null || UNSAFE.matcher(input).find()) return List.of();
        Map<String,Change> result = new LinkedHashMap<>(); Set<String> conflicts = new HashSet<>();
        for (Fact f : candidates.stream().limit(20).toList()) {
            try {
                boolean delete = "DELETE".equals(f.operation());
                if (!"self".equals(f.subject()) || !Double.isFinite(f.confidence()) || f.confidence() > 1 || f.confidence() < config.getMinConfidence()
                        || f.evidence() == null || f.evidence().length() < 2 || !input.contains(f.evidence())
                        || !(f.evidence().contains("我") || f.evidence().contains("以后回答"))
                        || (!delete && (f.value() == null || f.value().isBlank())) || (f.value() != null && f.value().length() > 200)
                        || !Set.of("ADD", "UPDATE", "DELETE").contains(f.operation())) continue;
                if (!delete && f.evidence().matches("(?s).*(不是|不再|不想|不会|不喜欢|[?？]).*")) continue;
                String value = f.value() == null ? "" : f.value().strip(); String key;
                if ("preference".equals(f.category()) && "response.verbosity".equals(f.attribute())) {
                    if (delete && f.evidence().matches("(?s).*(回答|回复|风格|偏好).*")) value = "deleted";
                    else if (value.matches("简短|简洁|short|concise")) value = "concise";
                    else if (value.matches("详细|detail|detailed")) value = "detailed";
                    else continue;
                    if (!delete && !f.evidence().matches("(?s).*(简短|简洁|详细|concise|detailed).*")) continue;
                    key = "preference.response.verbosity";
                } else if ("profile".equals(f.category()) && "occupation".equals(f.attribute())) {
                    if (delete && f.evidence().matches("(?s).*(职业|工作|岗位).*")) value = "deleted";
                    else if (!f.evidence().contains(value)) continue;
                    if (!delete && !f.evidence().matches("(?s).*(我是|我目前是|我的职业|我.*转岗|我.*做).*")) continue;
                    key = "profile.occupation";
                } else if ("profile".equals(f.category()) && "skill".equals(f.attribute())) {
                    value = SKILLS.get(value.toLowerCase(Locale.ROOT));
                    if (value == null || !f.evidence().toLowerCase(Locale.ROOT).contains(value)) continue;
                    if (!"DELETE".equals(f.operation()) && !f.evidence().matches("(?s).*(我会|掌握|熟悉|擅长|使用).*")) continue;
                    key = "profile.skill." + value;
                } else if ("goal".equals(f.category()) && "interview.company".equals(f.attribute())) {
                    if (!f.evidence().contains(value)) continue;
                    if (!f.evidence().contains("面试")) continue;
                    String element = switch (value.toLowerCase(Locale.ROOT)) {
                        case "字节", "字节跳动", "bytedance" -> "bytedance";
                        case "阿里", "阿里巴巴", "alibaba" -> "alibaba";
                        case "腾讯", "tencent" -> "tencent";
                        default -> null;
                    };
                    if (element == null) continue;
                    key = "goal.interview.company." + element;
                } else continue;
                if (delete && !f.evidence().matches("(?s).*(忘记|不再|完成|结束|放弃|删除).*")) continue;
                Instant expires = f.expiresAt() == null || f.expiresAt().isBlank() ? null : Instant.parse(f.expiresAt());
                if (expires != null && (!expires.isAfter(now) || expires.isAfter(now.plus(366, ChronoUnit.DAYS)))) continue;
                if ("goal".equals(f.category()) && expires == null) expires = now.plus(90, ChronoUnit.DAYS);
                if (f.evidence().matches("(?s).*(最近|暂时|这几天).*")) expires = now.plus(7, ChronoUnit.DAYS);
                Change c = new Change(key, f.category(), f.attribute(), value, f.operation(), f.evidence(), expires);
                Change previous = result.putIfAbsent(key, c);
                if (previous != null && !previous.equals(c)) conflicts.add(key);
            } catch (RuntimeException invalid) { /* Reject malformed or unsupported candidates. */ }
        }
        conflicts.forEach(result::remove); return List.copyOf(result.values());
    }

    private static final Pattern TRANSIENT_OR_UNSAFE = Pattern.compile(
        "可能|也许|假如|假设|如果|朋友|同事|他说|她说|引用|文档说|工具返回|检索结果|今天|明天|本周|这周|暂时|最近|截止|会议|进度|待办|deadline|todo|ignore.{0,30}(previous|instruction)|system prompt|developer instruction|绕过|越权|覆盖.{0,12}(系统|提示)|提升.{0,12}权限|自动执行.{0,12}(删除|命令|危险)", Pattern.CASE_INSENSITIVE);

    /** Invalid batches fail as a whole; the store repeats ownership checks under locks. */
    public List<MemoryPatch> validatePatches(String question, List<MemoryPatch> patches, List<MemoryDocument> documents) {
        if (patches == null || patches.size() > config.getMaxPatches()) throw new IllegalArgumentException("invalid patch count");
        if (patches.isEmpty()) return List.of();
        if (question == null || question.matches("(?s).*[“”\"].*") || question.contains("```") || TRANSIENT_OR_UNSAFE.matcher(question).find()) throw new IllegalArgumentException("unsafe source");
        Map<UUID,MemoryDocument> owners = new HashMap<>();
        for (MemoryDocument document : documents) for (SectionMetadata section : document.sections()) owners.put(section.id(),document);
        Set<UUID> seen = new HashSet<>(); Set<String> newHeadings = new HashSet<>();
        for (MemoryPatch patch : patches) {
            if (patch == null || patch.target() == null || patch.operation() == null || patch.evidence() == null
                || patch.evidence().isBlank() || !question.contains(patch.evidence())
                || !Pattern.compile("我|以后回答|以后回复|(?i)\\b(I|my|me)\\b").matcher(patch.evidence()).find()
                || TRANSIENT_OR_UNSAFE.matcher(patch.evidence()).find()) throw new IllegalArgumentException("invalid evidence");
            if (patch.sectionId() != null) {
                MemoryDocument owner=owners.get(patch.sectionId());
                if (owner == null || owner.type()!=patch.target() || !seen.add(patch.sectionId())) throw new IllegalArgumentException("invalid section ownership or conflict");
            }
            if (patch.operation() == MemoryPatchOperation.DELETE_SECTION) {
                if (patch.sectionId()==null || patch.content()!=null || !patch.evidence().matches("(?is).*(忘记|删除|不再|forget|delete|remove).*"))
                    throw new IllegalArgumentException("invalid deletion");
            } else {
                String heading=patch.heading();
                if (heading == null || heading.isBlank() || heading.contains("\n") || heading.contains("\r")
                    || heading.codePointCount(0,heading.length())>config.getMaxHeadingCodePoints()
                    || patch.content()==null || patch.content().isBlank()
                    || MemoryContextService.bytes(patch.content())>config.getMaxSectionBytes()
                    || TRANSIENT_OR_UNSAFE.matcher(heading+"\n"+patch.content()).find()) throw new IllegalArgumentException("invalid section content");
                if (patch.sectionId()==null && !newHeadings.add(patch.target()+":"+heading.strip())) throw new IllegalArgumentException("duplicate new heading");
            }
        }
        return List.copyOf(patches);
    }
}
