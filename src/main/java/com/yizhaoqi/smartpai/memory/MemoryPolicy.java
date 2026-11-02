package com.yizhaoqi.smartpai.memory;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** Semantic decisions belong to the model; provenance, ownership and concurrency belong to the server. */
@Component
public class MemoryPolicy {
    private final MemoryProperties config;
    public MemoryPolicy(MemoryProperties config) { this.config=config; }

    public void validateSources(List<MemorySource> sources,List<Turn> provided,Turn current) {
        if(sources==null || sources.isEmpty() || sources.size()>16) throw new IllegalArgumentException("missing or excessive sources");
        Map<Long,Turn> turns=new HashMap<>(); provided.forEach(t -> turns.put(t.id(),t));
        boolean currentUser=false;
        Set<String> seen=new HashSet<>();
        for(var source:sources) {
            if(source==null || source.quote()==null || source.quote().isBlank() || MemoryContextService.bytes(source.quote())>8192
                    || !Set.of("user","assistant").contains(source.role()) || !seen.add(source.turnId()+":"+source.role()+":"+source.quote()))
                throw new IllegalArgumentException("invalid source reference");
            Turn turn=turns.get(source.turnId());
            if(turn==null || turn.userId()!=current.userId() || !turn.sessionId().equals(current.sessionId()) || turn.id()>current.id())
                throw new IllegalArgumentException("source not in server-provided context");
            if(source.role().equals("assistant") && turn.id()==current.id()) throw new IllegalArgumentException("current assistant answer is not user evidence");
            String original=source.role().equals("user") ? turn.question():turn.answer();
            if(original==null || !original.contains(source.quote())) throw new IllegalArgumentException("source quote mismatch");
            if(turn.id()==current.id() && source.role().equals("user")) currentUser=true;
        }
        if(!currentUser) throw new IllegalArgumentException("current user evidence is required");
    }
    public List<MemoryCandidate> validateCandidates(List<MemoryCandidate> candidates,List<Turn> provided,Turn current,List<CatalogDocument> catalog) {
        if(candidates==null || candidates.size()>config.getMaxPatches()) throw new IllegalArgumentException("invalid candidate count");
        Map<UUID,MemoryType> owners=new HashMap<>();
        catalog.forEach(d -> d.sections().forEach(s -> owners.put(s.id(),d.type())));
        for(var candidate:candidates) {
            if(candidate==null || candidate.target()==null || candidate.candidate()==null || candidate.candidate().isBlank()
                    || MemoryContextService.bytes(candidate.candidate())>config.getMaxSectionBytes()
                    || candidate.targetSectionId()!=null && owners.get(candidate.targetSectionId())!=candidate.target())
                throw new IllegalArgumentException("invalid candidate or target");
            validateSources(candidate.sources(),provided,current);
        }
        return List.copyOf(candidates);
    }
    public List<MemoryPatch> validatePatches(List<MemoryPatch> patches,List<Turn> provided,Turn current,List<MemoryDocument> documents) {
        if(patches==null || patches.size()>config.getMaxPatches()) throw new IllegalArgumentException("invalid patch count");
        Map<UUID,MemoryDocument> owners=new HashMap<>(); Map<UUID,SectionMetadata> sections=new HashMap<>();
        documents.forEach(d -> d.sections().forEach(s -> { owners.put(s.id(),d); sections.put(s.id(),s); }));
        Set<UUID> seen=new HashSet<>(); Set<String> headings=new HashSet<>();
        for(var patch:patches) {
            if(patch==null || patch.target()==null || patch.operation()==null || patch.decision()==null
                    || patch.reason()==null || patch.reason().isBlank() || patch.reason().length()>1000 || patch.expectedSectionVersion()==null)
                throw new IllegalArgumentException("invalid patch shape or reason");
            validateSources(patch.sources(),provided,current);
            SectionMetadata old=null;
            if(patch.sectionId()!=null) {
                if(!seen.add(patch.sectionId()) || !owners.containsKey(patch.sectionId()) || owners.get(patch.sectionId()).type()!=patch.target())
                    throw new IllegalArgumentException("invalid section ownership");
                old=sections.get(patch.sectionId());
                if(patch.expectedSectionVersion()!=old.sectionVersion()) throw new MemoryVersionConflict();
            } else if(patch.expectedSectionVersion()!=0) throw new IllegalArgumentException("new section version must be zero");
            if(patch.operation()==MemoryPatchOperation.DELETE_SECTION) {
                if(old==null || patch.content()!=null || patch.decision()!=MemoryDecision.DELETE
                        || patch.sources().stream().noneMatch(s -> s.turnId()==current.id() && s.role().equals("user")
                            && s.quote().matches("(?is).*(忘记|删除|不再记|别再记|forget|delete|remove).*")))
                    throw new IllegalArgumentException("explicit deletion evidence is required");
            } else {
                if(patch.heading()==null || patch.heading().isBlank() || patch.heading().contains("\n") || patch.heading().contains("\r")
                        || patch.heading().codePointCount(0,patch.heading().length())>config.getMaxHeadingCodePoints()
                        || patch.description()==null || patch.description().isBlank() || patch.description().contains("\n") || patch.description().contains("\r")
                        || patch.description().codePointCount(0,patch.description().length())>config.getIndexDescriptionCodePoints()
                        || patch.content()==null || patch.content().isBlank() || MemoryContextService.bytes(patch.content())>config.getMaxSectionBytes()
                        || patch.decision()==MemoryDecision.DELETE)
                    throw new IllegalArgumentException("invalid section content or description");
                if(old==null && patch.decision()!=MemoryDecision.CREATE || old!=null && !old.deleted() && Set.of(MemoryDecision.CREATE,MemoryDecision.RESTORE).contains(patch.decision())
                        || old!=null && old.deleted() && patch.decision()!=MemoryDecision.RESTORE)
                    throw new IllegalArgumentException("decision does not match section state");
                if(!headings.add(patch.target()+":"+patch.heading().strip())) throw new IllegalArgumentException("duplicate target heading");
            }
        }
        return List.copyOf(patches);
    }
}
