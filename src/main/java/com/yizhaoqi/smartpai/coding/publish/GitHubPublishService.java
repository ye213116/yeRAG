package com.yizhaoqi.smartpai.coding.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.agent.profile.WorkspacePermission;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import com.yizhaoqi.smartpai.coding.task.*;
import com.yizhaoqi.smartpai.coding.workspace.GitHubRepositoryClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

/** Explicit user action only. This service is deliberately not an AgentTool. */
@Service
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class GitHubPublishService {
    private final CodingTaskStore store;
    private final CodingProperties config;
    private final GitHubRepositoryClient github;
    public GitHubPublishService(CodingTaskStore store,CodingProperties config,GitHubRepositoryClient github) {
        this.store=store;this.config=config;this.github=github;
    }
    public record Confirmation(String baseSha,String changeSetHash,String verificationId) {}
    public String publish(long owner,String id,Confirmation confirmation) {
        Task task=store.task(owner,id);Workspace workspace=store.workspace(owner,task.workspaceId());
        var repository=config.repository(workspace.repositoryId(),owner);
        if(!repository.getAllowedPermissions().contains(WorkspacePermission.WRITE) || !task.permissions().contains(WorkspacePermission.WRITE) || !workspace.permissions().contains(WorkspacePermission.WRITE))
            throw new SecurityException("Publishing requires workspace write permission");
        JsonNode result=task.result();
        if(confirmation==null || task.status()!=Status.REVIEW_READY || result==null || !result.path("ok").asBoolean()
                || result.path("changes").isEmpty() || result.path("cleanupPending").asBoolean()
                || !workspace.baseSha().equals(confirmation.baseSha())
                || !result.path("changeSetHash").asText().equals(confirmation.changeSetHash())
                || !result.path("verification").path("id").isTextual()
                || !result.path("verification").path("id").asText().equals(confirmation.verificationId())
                || !result.path("changeSetHash").asText().equals(result.path("verification").path("changeSetHash").asText()))
            throw CodingTaskStore.conflict("审核快照或验证记录已失效，请重新检查成果");
        if("CREATED".equals(task.publishState())) return task.prUrl();
        String token=CodingProperties.secret(repository.getPublishTokenEnv());
        if(token.isBlank()) throw new IllegalStateException("Repository publishing credential is not configured");
        if(!store.beginPublish(owner,id)) throw CodingTaskStore.conflict("PR 正在发布，请稍后查询任务状态");
        try {
            String prefix="/repos/"+repository.getFullName();
            String branch="yerag/task-"+id;
            String ownerName=repository.getFullName().split("/")[0];
            JsonNode existing=github.request("GET",prefix+"/pulls?state=all&head="+GitHubRepositoryClient.segment(ownerName+":"+branch)+"&base="+GitHubRepositoryClient.segment(workspace.branch()),null,token,false);
            if(existing.isArray() && !existing.isEmpty()) return complete(id,existing.get(0).path("html_url").asText());
            if(!workspace.baseSha().equals(github.branch(repository,workspace.branch())))
                throw CodingTaskStore.conflict("目标分支已变化，请新建 Workspace 更新基准并重新验证");
            JsonNode base=github.request("GET",prefix+"/git/commits/"+workspace.baseSha(),null,token,false);
            var entries=new ArrayList<Map<String,Object>>();
            for(JsonNode change:result.path("changes")) {
                String path=change.path("path").asText();validatePath(path);
                String mode=change.path("mode").asText();
                if(!Set.of("100644","100755").contains(mode)) throw new SecurityException("Unsupported file mode");
                var entry=new LinkedHashMap<String,Object>();entry.put("path",path);entry.put("mode",mode);entry.put("type","blob");
                if(change.path("content").isNull()) entry.put("sha",null);
                else if(change.path("content").isTextual()) entry.put("content",change.path("content").textValue());
                else throw new SecurityException("Unsupported change content");
                entries.add(entry);
            }
            String tree=write(owner,workspace,"POST",prefix+"/git/trees",Map.of("base_tree",base.path("tree").path("sha").asText(),"tree",entries),token,false).path("sha").asText();
            String refPath=prefix+"/git/ref/heads/"+branch;
            JsonNode reference=github.request("GET",refPath,null,token,true);
            if(reference==null) {
                String commit=write(owner,workspace,"POST",prefix+"/git/commits",Map.of("message","yeRAG task "+id,"tree",tree,"parents",List.of(workspace.baseSha())),token,false).path("sha").asText();
                write(owner,workspace,"POST",prefix+"/git/refs",Map.of("ref","refs/heads/"+branch,"sha",commit),token,false);
            } else {
                JsonNode head=github.request("GET",prefix+"/git/commits/"+reference.path("object").path("sha").asText(),null,token,false);
                if(!tree.equals(head.path("tree").path("sha").asText()) || !workspace.baseSha().equals(head.path("parents").path(0).path("sha").asText()))
                    throw CodingTaskStore.conflict("任务分支包含其他变更，拒绝覆盖");
            }
            String description="## Changes\n\nCloud Coding task `"+id+"`. The owner approved the frozen Diff.\n\n"
                    +"## Validation\n\nStatus: **"+result.path("verification").path("status").asText()+"**.\n\n"
                    +"Fixed Maven test recipe; tests="+result.path("verification").path("tests").asInt()
                    +", failures="+result.path("verification").path("failures").asInt()+", errors="+result.path("verification").path("errors").asInt()
                    +".\n\nBase: `"+workspace.baseSha()+"`\n\nChange fingerprint: `"+confirmation.changeSetHash()+"`\n";
            JsonNode pr=write(owner,workspace,"POST",prefix+"/pulls",Map.of("title","yeRAG task "+id,"head",branch,"base",workspace.branch(),"body",description),token,false);
            return complete(id,pr.path("html_url").asText());
        } catch(RuntimeException failure) {
            store.publication(id,"FAILED",null);store.event(id,"publish_failed",Map.of("errorType",failure.getClass().getSimpleName()));throw failure;
        }
    }
    private JsonNode write(long owner,Workspace workspace,String method,String path,Object body,String token,boolean missing) {
        if(!config.repository(workspace.repositoryId(),owner).getAllowedPermissions().contains(WorkspacePermission.WRITE))
            throw new SecurityException("Repository publishing permission was revoked");
        if(!store.workspace(owner,workspace.id()).permissions().contains(WorkspacePermission.WRITE))
            throw new SecurityException("Publishing permission was revoked");
        return github.request(method,path,body,token,missing);
    }
    private String complete(String id,String url) {
        if(url==null || !url.matches("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/[0-9]+"))
            throw new IllegalStateException("GitHub returned an invalid PR URL");
        store.publication(id,"CREATED",url);store.event(id,"pr_created",Map.of("url",url));return url;
    }
    private static void validatePath(String path) {
        if(path.isBlank() || path.startsWith("/") || path.contains("\\") || path.length()>1024
                || Arrays.stream(path.split("/")).anyMatch(p -> p.equals("..") || p.equals(".git") || p.equals(".yerag-cache"))
                || path.chars().anyMatch(c -> c<32)) throw new SecurityException("Invalid changed file path");
    }
}
