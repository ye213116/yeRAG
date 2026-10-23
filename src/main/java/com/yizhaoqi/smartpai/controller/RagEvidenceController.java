package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.rag.trace.EvidenceStore;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/rag/runs")
public class RagEvidenceController {
    private final EvidenceStore store;
    private final UserRepository users;
    public RagEvidenceController(EvidenceStore store,UserRepository users) { this.store=store; this.users=users; }
    @GetMapping("/{runId}")
    public JsonNode get(@PathVariable String runId,Principal principal) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var user=users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        for(String owner:java.util.List.of("name:"+user.getUsername(),"id:"+user.getId())) {
            try { return store.read(runId,owner); }
            catch(java.io.FileNotFoundException | java.nio.file.NoSuchFileException | SecurityException | IllegalArgumentException missing) { /* Do not reveal existence. */ }
            catch(Exception unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence unavailable"); }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
