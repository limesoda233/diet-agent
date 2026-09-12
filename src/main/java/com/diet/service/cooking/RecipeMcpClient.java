package com.diet.service.cooking;

import com.diet.DietApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.system.ApplicationHome;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Lazily launches one read-only MCP server per application, released by Spring at shutdown. */
public class RecipeMcpClient implements RecipeProvider,AutoCloseable {
    private final CookingProperties properties;
    private final ObjectMapper json=new ObjectMapper();
    private McpClientWrapper client;
    private Toolkit toolkit;
    private Path argumentsFile;
    private Path processIdFile;
    private boolean closed;
    private final Set<ProcessHandle> ownedProcesses=new HashSet<>();

    public RecipeMcpClient(CookingProperties properties) { this.properties=properties; }
    RecipeMcpClient(CookingProperties properties,McpClientWrapper connectedClient) { this(properties); this.client=connectedClient; }
    RecipeMcpClient(CookingProperties properties,McpClientWrapper connectedClient,ProcessHandle ownedProcess) {
        this(properties,connectedClient); ownedProcesses.add(ownedProcess);
    }

    private synchronized McpClientWrapper connected() {
        if(closed) throw new IllegalStateException("recipe_mcp_closed");
        if(client!=null) return client;
        try {
            var args=new ArrayList<String>();
            processIdFile=Files.createTempFile("diet-recipe-mcp-",".pid");
            args.add("-Ddiet.cooking.process-id-file="+processIdFile);
            var source=new ApplicationHome(DietApplication.class).getSource();
            String jar=properties.getServerJar().isBlank() && source!=null && source.isFile()?source.getAbsolutePath():properties.getServerJar();
            if(!jar.isBlank()) { args.add("-jar"); args.add(Path.of(jar).toAbsolutePath().toString()); }
            else {
                args.add("-cp"); args.add(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
                args.add(DietApplication.class.getName());
            }
            args.add("--recipe-mcp-server");
            argumentsFile=Files.createTempFile("diet-recipe-mcp-",".args");
            Files.writeString(argumentsFile,args.stream().map(RecipeMcpClient::quoteArgument).collect(java.util.stream.Collectors.joining("\n")));
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            client=McpClientBuilder.create("diet-recipes").stdioTransport(executable,"@"+argumentsFile)
                    .timeout(properties.getInitializationTimeout()).initializationTimeout(properties.getInitializationTimeout())
                    .buildAsync().block(properties.getInitializationTimeout());
            client.initialize().block(properties.getInitializationTimeout());
            captureOwnedProcess();
            if(ownedProcesses.isEmpty()) throw new IllegalStateException("recipe_mcp_process_identity_unavailable");
            var tools=client.listTools().block(properties.getInitializationTimeout());
            if(tools==null || !tools.stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()).equals(RecipeMcpServer.TOOLS))
                throw new IllegalStateException("recipe_mcp_tools_invalid");
            toolkit=new Toolkit(); toolkit.registerMcpClient(client).block(properties.getInitializationTimeout());
            return client;
        } catch(Exception failure) {
            disconnect(); throw new IllegalStateException("recipe_mcp_unavailable",failure);
        }
    }
    private static String quoteArgument(String text) { return "\""+text.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
    public Set<String> toolNames() { return connected().listTools().block(properties.getTimeout()).stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()); }
    public Set<String> registeredToolNames() { connected(); return toolkit.getToolNames(); }
    @Override public synchronized Map<String,Object> call(String tool,Map<String,Object> args) {
        if(!RecipeMcpServer.TOOLS.contains(tool)) throw new IllegalArgumentException("未知食谱工具");
        final McpSchema.CallToolResult result;
        try { result=connected().callTool(tool,args).block(properties.getTimeout()); }
        catch(RuntimeException failure) { disconnect(); throw new IllegalStateException("recipe_mcp_unavailable",failure); }
        if(result==null) throw new IllegalStateException("recipe_mcp_empty_response");
        if(Boolean.TRUE.equals(result.isError())) throw new IllegalArgumentException("食谱工具拒绝请求：请核对食谱 ID、食材和限制");
        try {
            if(!(result.structuredContent() instanceof Map<?,?>)) throw new IllegalStateException("recipe_mcp_invalid_response");
            return json.convertValue(result.structuredContent(),new TypeReference<Map<String,Object>>(){});
        } catch(RuntimeException failure) { throw new IllegalStateException("recipe_mcp_invalid_response",failure); }
    }
    private void disconnect() {
        captureOwnedProcess();
        var disconnected=client;
        client=null; toolkit=null;
        var cleanup=new FutureTask<Void>(()-> { if(disconnected!=null) disconnected.close(); return null; });
        var thread=new Thread(cleanup,"recipe-mcp-cleanup"); thread.setDaemon(true); thread.start();
        boolean cleanupCompleted=false;
        try { cleanup.get(1,TimeUnit.SECONDS); cleanupCompleted=true; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(ExecutionException | TimeoutException ignored) { /* Force only this client's child below. */ }
        finally {
            cleanup.cancel(true);
            captureOwnedProcess();
            if(!cleanupCompleted && disconnected!=null && ownedProcesses.isEmpty()) closed=true;
            for(var process:ownedProcesses) if(process.isAlive()) process.destroyForcibly();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            for(var process:ownedProcesses) {
                try { process.onExit().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch(ExecutionException | TimeoutException ignored) { /* Bound shutdown even if the OS cannot reap immediately. */ }
            }
            // Do not launch another child if the OS has not terminated the previous one.
            if(ownedProcesses.stream().anyMatch(ProcessHandle::isAlive)) closed=true;
            ownedProcesses.removeIf(process->!process.isAlive());
            if(argumentsFile!=null) { try { Files.deleteIfExists(argumentsFile); } catch(java.io.IOException ignored) {} argumentsFile=null; }
            if(processIdFile!=null) { try { Files.deleteIfExists(processIdFile); } catch(java.io.IOException ignored) {} processIdFile=null; }
        }
    }
    private void captureOwnedProcess() {
        if(processIdFile==null) return;
        try {
            String value=Files.readString(processIdFile).trim();
            if(value.isEmpty()) return;
            ProcessHandle.of(Long.parseLong(value))
                    .filter(process->process.parent().map(parent->parent.pid()==ProcessHandle.current().pid()).orElse(false))
                    .ifPresent(ownedProcesses::add);
        } catch(java.io.IOException | NumberFormatException ignored) { /* Initialization may fail before the PID handshake. */ }
    }
    synchronized Set<Long> ownedProcessIds() { return ownedProcesses.stream().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet()); }
    @Override public synchronized void close() { closed=true; disconnect(); }
}
