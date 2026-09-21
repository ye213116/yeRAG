package com.yizhaoqi.smartpai.memory;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** A strict editor for the controlled Markdown stored in memory_document. */
@Component
public class MemoryDocumentEditor {
    public record ParsedSection(String heading, String content) {}
    public record ParsedDocument(String title, List<ParsedSection> sections) {}
    public record EditResult(String content, List<ParsedSection> sections) {}

    public ParsedDocument parse(String markdown) {
        if (markdown == null) throw invalid("Missing document");
        String title = null, heading = null;
        StringBuilder body = new StringBuilder();
        List<ParsedSection> sections = new ArrayList<>();
        Set<String> headings = new HashSet<>();
        Fence fence = new Fence();
        for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (fence.consume(line)) {
                if (heading == null) throw invalid("Code outside a section");
                body.append(line).append('\n');
                continue;
            }
            if (line.startsWith("# ")) {
                if (title != null || heading != null || line.substring(2).isBlank()) throw invalid("Multiple or empty document titles");
                title = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                if (title == null) throw invalid("Section before document title");
                if (heading != null) sections.add(new ParsedSection(heading, body.toString().strip()));
                heading = checkedHeading(line.substring(3));
                if (!headings.add(heading)) throw invalid("Duplicate section heading");
                body.setLength(0);
            } else if (heading != null) {
                body.append(line).append('\n');
            } else if (!line.isBlank()) throw invalid("Text outside a section");
        }
        if (fence.open || title == null) throw invalid("Unclosed fence or missing title");
        if (heading != null) sections.add(new ParsedSection(heading, body.toString().strip()));
        return new ParsedDocument(title, List.copyOf(sections));
    }

    public EditResult apply(String currentMarkdown, List<ResolvedMemoryPatch> patches,
                            Map<UUID, SectionMetadata> catalog) {
        ParsedDocument parsed = parse(currentMarkdown);
        List<ParsedSection> sections = new ArrayList<>(parsed.sections());
        Set<UUID> touched = new HashSet<>();
        // Resolve identity against the original document so renames never change another patch's target.
        Map<UUID, ParsedSection> identities = new HashMap<>();
        Set<String> catalogHeadings = new HashSet<>();
        for (SectionMetadata metadata : catalog.values()) {
            if (metadata.deleted()) continue;
            if (!catalogHeadings.add(metadata.heading())) throw invalid("Duplicate active catalog heading");
            ParsedSection found = parsed.sections().stream().filter(s -> s.heading().equals(metadata.heading())).findFirst()
                    .orElseThrow(() -> invalid("Active catalog section missing from document"));
            identities.put(metadata.id(), found);
        }
        if (identities.size()!=parsed.sections().size()) throw invalid("Document section missing from catalog");
        for (ResolvedMemoryPatch patch : patches) {
            if (patch == null || patch.sectionId() == null || patch.operation() == null || !touched.add(patch.sectionId()))
                throw invalid("Invalid or duplicate patch");
            SectionMetadata metadata = catalog.get(patch.sectionId());
            ParsedSection previous = identities.get(patch.sectionId());
            if (patch.operation() == MemoryPatchOperation.DELETE_SECTION) {
                if (metadata == null || patch.content() != null) throw invalid("Invalid delete");
                if (previous != null) sections.removeIf(s -> s == previous);
            } else {
                String heading = checkedHeading(patch.heading());
                if (patch.content() == null) throw invalid("Missing section body");
                String body = patch.content().strip();
                ParsedDocument validated = parse("# Validation\n\n## " + heading + "\n\n" + body + "\n");
                if (validated.sections().size() != 1) throw invalid("Section body contains a section heading");
                ParsedSection replacement = new ParsedSection(heading, body);
                if (previous == null) sections.add(replacement);
                else {
                    int position = -1;
                    for (int i=0;i<sections.size();i++) if (sections.get(i)==previous) { position=i; break; }
                    if (position<0) throw invalid("Missing original section");
                    sections.set(position,replacement);
                }
            }
        }
        Set<String> headings = new HashSet<>();
        StringBuilder output = new StringBuilder("# ").append(parsed.title()).append('\n');
        for (ParsedSection section : sections) {
            if (!headings.add(section.heading())) throw invalid("Duplicate section heading");
            output.append("\n## ").append(section.heading()).append("\n\n").append(section.content()).append('\n');
        }
        return new EditResult(output.toString(), List.copyOf(sections));
    }

    /** First ordinary paragraph; code, headings, lists, blockquotes and tables are not descriptions. */
    public String firstParagraph(String content) {
        Fence fence = new Fence();
        StringBuilder paragraph = new StringBuilder();
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            boolean code = fence.consume(line);
            String stripped = line.strip();
            boolean ordinary = !code && !stripped.isEmpty() && !stripped.startsWith("#")
                    && !stripped.startsWith(">") && !stripped.startsWith("|")
                    && !stripped.matches("(?:[-+*]\\s+|\\d+[.)]\\s+).*" )
                    && !stripped.matches("[-*_]{3,}") && !line.startsWith("    ") && !line.startsWith("\t");
            if (ordinary) {
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(stripped);
            } else if (!paragraph.isEmpty()) break;
        }
        return paragraph.toString().replaceAll("(?U)\\s+", " ").strip();
    }

    private static String checkedHeading(String heading) {
        if (heading == null || heading.isBlank() || heading.contains("\n") || heading.contains("\r"))
            throw invalid("Invalid section heading");
        return heading.trim();
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static final class Fence {
        boolean open;
        char marker;
        int length;
        boolean consume(String line) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String candidate = line.substring(indent);
            if (indent > 3 || candidate.isEmpty()) return open;
            char first = candidate.charAt(0);
            int run = 0;
            while (run < candidate.length() && candidate.charAt(run) == first) run++;
            if (!open && (first == '`' || first == '~') && run >= 3) {
                if (first == '`' && candidate.substring(run).contains("`")) return false;
                open = true; marker = first; length = run;
                return true;
            }
            if (open && first == marker && run >= length && candidate.substring(run).isBlank()) {
                open = false;
                return true;
            }
            return open;
        }
    }
}
