package com.yizhaoqi.smartpai.artifact;

import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Recheck source permissions when reusing a report, not just when it was generated. */
@Service
public class ArtifactService {
    private final ArtifactStore store;
    private final HybridSearchService search;
    public ArtifactService(ArtifactStore store, HybridSearchService search) { this.store = store; this.search = search; }

    public ArtifactStore.Page list(long owner, int offset, SourceMode mode) {
        if (offset < 0 || offset > 100_000) throw ArtifactStore.badRequest("无效分页位置");
        var rows = store.list(owner, offset, 20);
        var visible = new ArrayList<ArtifactStore.Summary>();
        for (var row : rows) if (allowed(owner, row.sources(), mode)) visible.add(ArtifactStore.summary(row));
        return new ArtifactStore.Page(List.copyOf(visible), rows.size() == 20 ? offset + 20 : null);
    }

    public ArtifactStore.Version read(long owner, String id, Integer version, SourceMode mode) {
        var result = store.read(owner, id, version);
        check(owner, result.sources(), mode);
        return result;
    }

    public List<ArtifactStore.Summary> versions(long owner, String id, int before) {
        read(owner, id, null, SourceMode.AUTO);
        // Each historical version can have different source permissions.
        return store.versions(owner, id, before).stream()
                .filter(v -> allowed(owner, store.read(owner, id, v.version()).sources(), SourceMode.AUTO)).toList();
    }

    public ArtifactStore.Version save(long owner, ArtifactStore.Save input, List<SourceReference> added, SourceMode mode) {
        if (input == null) throw ArtifactStore.badRequest("缺少报告内容");
        var sources = new LinkedHashMap<Integer, SourceReference>();
        added.forEach(source -> sources.put(source.number(), source));
        if (input.id() != null) {
            // Preserve source dependencies when revising a report, including sources omitted by the model.
            var old = read(owner, input.id(), input.expectedVersion(), mode);
            for (var source : old.sources()) {
                boolean present = sources.values().stream().anyMatch(s -> sameSource(s, source));
                if (present) continue;
                int number = source.number();
                while (sources.containsKey(number)) number++;
                sources.put(number, new SourceReference(number, source.kind(), source.title(), source.fileMd5(),
                        source.chunkId(), source.url(), source.fetchedAt(), source.evidence()));
            }
        }
        if (sources.size() > 20) throw ArtifactStore.badRequest("一份报告最多保留 20 项来源");
        if (input.content() != null) {
            var citations = java.util.regex.Pattern.compile("\\[来源#(\\d+)]").matcher(input.content());
            while (citations.find()) {
                try {
                    if (!sources.containsKey(Integer.parseInt(citations.group(1)))) throw ArtifactStore.badRequest("正文含未绑定的来源编号");
                } catch (NumberFormatException invalid) { throw ArtifactStore.badRequest("无效来源编号"); }
            }
        }
        check(owner, List.copyOf(sources.values()), mode);
        return store.save(owner, input, List.copyOf(sources.values()));
    }

    private boolean sameSource(SourceReference a, SourceReference b) {
        return java.util.Objects.equals(a.kind(), b.kind()) && java.util.Objects.equals(a.fileMd5(), b.fileMd5())
                && java.util.Objects.equals(a.chunkId(), b.chunkId()) && java.util.Objects.equals(a.url(), b.url())
                && java.util.Objects.equals(a.evidence(), b.evidence());
    }

    private void check(long owner, List<SourceReference> sources, SourceMode mode) {
        if (!allowed(owner, sources, mode)) throw ArtifactStore.missing();
    }
    private boolean allowed(long owner, List<SourceReference> sources, SourceMode mode) {
        for (SourceReference source : sources) {
            if ("knowledge".equals(source.kind())) {
                if (mode == SourceMode.WEB) return false;
                if (search.readDocumentWithPermission(source.fileMd5(), source.chunkId() == null ? 0 : source.chunkId(), 1,
                        Long.toString(owner)).isEmpty()) return false;
            } else if ("web".equals(source.kind())) {
                if (mode == SourceMode.KNOWLEDGE) return false;
            } else return false;
        }
        return true;
    }
}
