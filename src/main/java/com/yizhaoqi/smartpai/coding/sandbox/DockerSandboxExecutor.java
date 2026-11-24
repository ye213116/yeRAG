package com.yizhaoqi.smartpai.coding.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.coding.*;
import com.yizhaoqi.smartpai.coding.CodingTypes.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.net.ssl.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed container specs only. Model arguments are data passed to an immutable helper image. */
@Component
@ConditionalOnProperty(name="ai.coding.enabled",havingValue="true")
public class DockerSandboxExecutor implements SandboxExecutor {
    private final CodingProperties config;
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI root;
    private final Map<String,String> active=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public DockerSandboxExecutor(CodingProperties config,ObjectMapper json) {
        this(config,json,secureClient(config),URI.create(Objects.requireNonNull(config.getDockerUrl(),"Coding requires an HTTPS Docker endpoint")));
        if(!"https".equals(root.getScheme()) || root.getUserInfo()!=null || root.getQuery()!=null)
            throw new IllegalArgumentException("Docker requires a dedicated HTTPS endpoint with mTLS");
    }
    DockerSandboxExecutor(CodingProperties config,ObjectMapper json,HttpClient client,URI root) {
        this.config=config;this.json=json;this.client=client;this.root=root;
        if(config.getMaxWorkspaceBytes()<1024*1024 || config.getMaxResultBytes()<1024 || config.getMaxResultBytes()>8*1024*1024
                || config.getMaxLogBytes()<1024 || config.getMaxLogBytes()>config.getMaxResultBytes()
                || config.getCommandTimeout().isNegative() || config.getCommandTimeout().isZero())
            throw new IllegalArgumentException("Invalid sandbox resource settings");
    }
    private static HttpClient secureClient(CodingProperties config) {
        try {
            char[] password=CodingProperties.secret(config.getDockerStorePasswordEnv()).toCharArray();
            KeyStore trust=KeyStore.getInstance("PKCS12"),keys=KeyStore.getInstance("PKCS12");
            try(var in=Files.newInputStream(Path.of(Objects.requireNonNull(config.getDockerTrustStore(),"Docker trust store is required")))) { trust.load(in,password); }
            try(var in=Files.newInputStream(Path.of(Objects.requireNonNull(config.getDockerKeyStore(),"Docker client key store is required")))) { keys.load(in,password); }
            var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),null);
            Arrays.fill(password,'\0');
            return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
        } catch(Exception failure) { throw new IllegalStateException("Cannot initialize Docker mTLS client",failure); }
    }
    @Override public void preflight() {
        JsonNode info=api("GET","/info",null,false);
        if(!"linux".equals(info.path("OSType").asText()) || !info.path("MemoryLimit").asBoolean()
                || !info.path("PidsLimit").asBoolean() || !info.path("CpuCfsQuota").asBoolean())
            throw new IllegalStateException("Docker resource isolation is unavailable");
        for(String network:List.of(config.getCloneNetwork(),config.getBuildNetwork())) {
            if(!network.matches("[a-zA-Z0-9_-]+") || !api("GET","/networks/"+network,null,false).path("Internal").asBoolean())
                throw new IllegalStateException("Coding networks must be internal networks with controlled egress proxies");
        }
        if(!URI.create(config.getProxyUrl()).getHost().matches("[a-zA-Z0-9_-]+")) throw new IllegalStateException("Invalid coding egress proxy");
        api("GET","/images/"+config.getImage().replace("/","%2F")+"/json",null,false);
    }
    private String prefix(String workspace) { CodingTypes.uuid(workspace);return "yerag-coding-"+workspace; }
    private void volume(String name) {
        api("POST","/volumes/create",Map.of("Name",name,"Driver","local","DriverOpts",Map.of(
                "type","tmpfs","device","tmpfs","o","size="+config.getMaxWorkspaceBytes()+",uid=10001,gid=10001,mode=0700")),false);
    }
    @Override public void prepare(Workspace w,CodingProperties.Repository repository,Runnable authorization) {
        authorization.run();preflight();
        String prefix=prefix(w.id());volume(prefix+"-work");volume(prefix+"-base");
        var keeper=spec(w.id(),List.of("sleep","infinity"),List.of(),false,true,true);
        JsonNode created=api("POST","/containers/create?name="+prefix+"-keeper",keeper,false);
        api("POST","/containers/"+created.path("Id").asText()+"/start",null,false);
        var args=new LinkedHashMap<String,Object>();args.put("repository",repository.getFullName());args.put("sha",w.baseSha());
        String token=CodingProperties.secret(repository.getReadTokenEnv());
        JsonNode result=run(w,Operation.SETUP,args,Duration.ofMinutes(2),authorization,token);
        if(!result.path("ok").asBoolean()) throw new IllegalStateException("Workspace preparation failed");
    }
    @Override public JsonNode execute(Workspace w,Operation op,Map<String,Object> args,Duration timeout,Runnable authorization) {
        if(op==Operation.SETUP) throw new IllegalArgumentException("Setup is not a model operation");
        return run(w,op,args,timeout,authorization,"");
    }
    private JsonNode run(Workspace w,Operation op,Map<String,Object> args,Duration timeout,Runnable authorization,String token) {
        authorization.run();
        if(op==Operation.TEST || op==Operation.COMMAND) timeout=timeout.compareTo(config.getCommandTimeout())>0?config.getCommandTimeout():timeout;
        var payload=new LinkedHashMap<String,Object>();payload.put("operation",op.name());payload.put("arguments",args);
        payload.put("maxLogBytes",config.getMaxLogBytes());payload.put("maxResultBytes",config.getMaxResultBytes());
        payload.put("maxWorkspaceBytes",config.getMaxWorkspaceBytes());payload.put("timeoutSeconds",Math.max(1,timeout.toSeconds()-2));
        List<String> env=new ArrayList<>();
        try {
            byte[] encoded=json.writeValueAsBytes(payload);
            if(encoded.length>65536) throw new IllegalArgumentException("Sandbox request exceeds container argument limit");
            env.add("YERAG_REQUEST="+Base64.getEncoder().encodeToString(encoded));
        }
        catch(Exception invalid) { throw new IllegalArgumentException("Invalid sandbox request"); }
        boolean network=op==Operation.TEST || op==Operation.SETUP;
        if(network) {
            env.add("HTTPS_PROXY="+config.getProxyUrl());env.add("HTTP_PROXY="+config.getProxyUrl());env.add("https_proxy="+config.getProxyUrl());env.add("http_proxy="+config.getProxyUrl());
            if(!token.isEmpty()) env.add("YERAG_CLONE_TOKEN="+token);
        }
        boolean writable=op==Operation.PATCH || op==Operation.COMMAND || op==Operation.TEST || op==Operation.SETUP;
        String id=api("POST","/containers/create?name="+prefix(w.id())+"-operation",spec(w.id(),List.of("python3","/opt/yerag/helper.py"),env,network,writable,op==Operation.SETUP),false).path("Id").asText();
        if(id.isBlank()) throw new IllegalStateException("Docker did not return a container");
        if(active.putIfAbsent(w.id(),id)!=null) { remove(id);throw new IllegalStateException("Concurrent workspace operation rejected"); }
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            authorization.run();api("POST","/containers/"+id+"/start",null,false);
            JsonNode state;
            do {
                authorization.run();
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline) throw new java.util.concurrent.CancellationException("Sandbox operation deadline exceeded");
                state=api("GET","/containers/"+id+"/json",null,false).path("State");
                if(!state.path("Running").asBoolean()) break;
                Thread.sleep(200);
            } while(true);
            if(state.path("OOMKilled").asBoolean()) return json.valueToTree(Map.of("ok",false,"error","sandbox_oom"));
            if(state.path("ExitCode").asInt(-1)!=0) return json.valueToTree(Map.of("ok",false,"error","sandbox_failed"));
            byte[] logs=request("GET","/containers/"+id+"/logs?stdout=true&stderr=false",null,false,config.getMaxResultBytes()+65536);
            byte[] decoded=demultiplex(logs,config.getMaxResultBytes());
            return json.readTree(decoded);
        } catch(InterruptedException cancelled) { Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Sandbox interrupted"); }
        catch(java.io.IOException invalid) { throw new IllegalStateException("Sandbox result is invalid"); }
        finally { try { remove(id); } finally { active.remove(w.id(),id); } }
    }
    private Map<String,Object> spec(String workspace,List<String> command,List<String> env,boolean network,boolean writable,boolean baseWritable) {
        String prefix=prefix(workspace);
        var host=new LinkedHashMap<String,Object>();
        host.put("NetworkMode",network?(command.contains("sleep")?"none":(baseWritable?config.getCloneNetwork():config.getBuildNetwork())):"none");
        host.put("ReadonlyRootfs",true);host.put("Memory",4L*1024*1024*1024);host.put("MemorySwap",4L*1024*1024*1024);
        host.put("NanoCpus",2_000_000_000L);host.put("PidsLimit",256);host.put("CapDrop",List.of("ALL"));
        host.put("SecurityOpt",List.of("no-new-privileges:true"));host.put("Tmpfs",Map.of("/tmp","rw,nosuid,nodev,size=268435456,uid=10001,gid=10001,mode=1777"));
        host.put("LogConfig",Map.of("Type","json-file","Config",Map.of("max-size","4m","max-file","1")));
        host.put("Mounts",List.of(Map.of("Type","volume","Source",prefix+"-work","Target","/workspace","ReadOnly",!writable),
                Map.of("Type","volume","Source",prefix+"-base","Target","/base","ReadOnly",!baseWritable)));
        // Bypass inherited image startup scripts: stdout is exclusively the helper's JSON protocol.
        return Map.of("Image",config.getImage(),"User","10001:10001","WorkingDir","/workspace","Entrypoint",command,"Cmd",List.of(),"Env",env,
                "Labels",Map.of("yerag.workspace",workspace),"HostConfig",host);
    }
    @Override public void stop(String workspace) {
        String id=active.get(workspace);
        if(id!=null) { remove(id);active.remove(workspace,id); }
        // Deterministic name also recovers a lost response, interrupted cleanup or process restart.
        remove(prefix(workspace)+"-operation");
    }
    private void remove(String id) {
        boolean interrupted=Thread.interrupted();
        try { api("DELETE","/containers/"+id+"?force=true&v=true",null,true); }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void destroy(String workspace) {
        stop(workspace);String prefix=prefix(workspace);remove(prefix+"-keeper");
        api("DELETE","/volumes/"+prefix+"-work",null,true);api("DELETE","/volumes/"+prefix+"-base",null,true);
    }
    private JsonNode api(String method,String path,Object body,boolean missing) {
        byte[] bytes=request(method,path,body,missing,8*1024*1024);
        try { return bytes.length==0?json.createObjectNode():json.readTree(bytes); }
        catch(Exception invalid) { throw new IllegalStateException("Invalid Docker response"); }
    }
    private byte[] request(String method,String path,Object body,boolean missing,int limit) {
        try {
            var builder=HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(10));
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            if(body!=null) builder.header("Content-Type","application/json");
            var response=com.yizhaoqi.smartpai.coding.http.BoundedHttp.send(client,builder.build(),limit,Duration.ofSeconds(10));
            if(missing && response.statusCode()==404) return new byte[0];
            if(response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("Docker operation failed (HTTP "+response.statusCode()+")");
            return response.body();
        } catch(InterruptedException cancelled) { Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Docker request interrupted"); }
        catch(java.io.IOException failed) { throw new IllegalStateException("Docker endpoint unavailable"); }
    }
    static byte[] demultiplex(byte[] bytes,int limit) throws IOException {
        var output=new ByteArrayOutputStream();int offset=0;
        while(offset<bytes.length) {
            if(bytes.length-offset<8) throw new IOException("Invalid Docker log frame");
            int length=ByteBuffer.wrap(bytes,offset+4,4).getInt();
            if(length<0 || length>bytes.length-offset-8 || output.size()+length>limit) throw new IOException("Invalid Docker log length");
            output.write(bytes,offset+8,length);offset+=8+length;
        }
        return output.toByteArray();
    }
}
