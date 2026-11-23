package com.yizhaoqi.smartpai.coding.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.coding.CodingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class GitHubRepositoryClient {
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI root;
    @org.springframework.beans.factory.annotation.Autowired
    public GitHubRepositoryClient(ObjectMapper json) {
        this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://api.github.com"));
    }
    GitHubRepositoryClient(ObjectMapper json,HttpClient client,URI root) { this.json=json; this.client=client; this.root=root; }
    public String branch(CodingProperties.Repository repository,String branch) {
        repository.validate();
        if(!repository.getAllowedBranches().contains(branch)) throw new SecurityException("Branch is not authorized");
        var response=request("GET","/repos/"+repository.getFullName()+"/commits/"+segment(branch),null,CodingProperties.secret(repository.getReadTokenEnv()),false);
        String sha=response.path("sha").asText();
        if(!sha.matches("[a-f0-9]{40}")) throw new IllegalStateException("Invalid GitHub commit");
        return sha;
    }
    public JsonNode request(String method,String path,Object body,String token,boolean missingAllowed) {
        try {
            if(!path.startsWith("/repos/") || path.contains("..")) throw new IllegalArgumentException("Invalid GitHub request");
            var builder=HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(30))
                    .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28");
            if(!token.isBlank()) builder.header("Authorization","Bearer "+token);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            if(body!=null) builder.header("Content-Type","application/json");
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                if(response.statusCode()==404 && missingAllowed) return null;
                if(response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("GitHub request failed (HTTP "+response.statusCode()+")");
                byte[] encoded=input.readNBytes(4*1024*1024+1);
                if(encoded.length>4*1024*1024) throw new IllegalStateException("GitHub response exceeded limit");
                return json.readTree(encoded);
            }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub request interrupted"); }
        catch(java.io.IOException failure) { throw new IllegalStateException("GitHub transport unavailable"); }
    }
    public static String segment(String value) { return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
}
