package com.diet.service.cooking;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Standalone public-data server. No Spring context, MySQL connection or LLM is started. */
public final class RecipeMcpServer {
    public static final Set<String> TOOLS=Set.of("search_recipes","get_recipe","suggest_substitutions");
    private RecipeMcpServer() {}
    public static void run() throws Exception {
        String processIdFile=System.getProperty("diet.cooking.process-id-file");
        if(processIdFile!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(processIdFile),Long.toString(ProcessHandle.current().pid()));
        System.setProperty("logback.configurationFile",Objects.requireNonNull(RecipeMcpServer.class.getResource("/diet/cooking/logback-mcp.xml")).toString());
        var eof=new CountDownLatch(1);
        InputStream input=new FilterInputStream(System.in) {
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                int count=super.read(bytes,offset,length); if(count<0) eof.countDown(); return count;
            }
            @Override public int read() throws IOException {
                int value=super.read(); if(value<0) eof.countDown(); return value;
            }
        };
        var json=new ObjectMapper(); var mapper=new JacksonMcpJsonMapper(json);
        var provider=new LocalRecipeProvider();
        var builder=McpServer.sync(new StdioServerTransportProvider(mapper,input,System.out))
                .serverInfo("diet-recipes","1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for(String name:TOOLS) {
            var tool=McpSchema.Tool.builder().name(name).description(description(name))
                    .inputSchema(mapper,json.writeValueAsString(schema(name))).build();
            builder.tool(tool,(exchange,args)->{
                try {
                    var result=provider.call(name,args);
                    return McpSchema.CallToolResult.builder().structuredContent(result).addTextContent(json.writeValueAsString(result)).isError(false).build();
                } catch(IllegalArgumentException invalid) {
                    return McpSchema.CallToolResult.builder().addTextContent(invalid.getMessage()).isError(true).build();
                } catch(Exception failure) {
                    return McpSchema.CallToolResult.builder().addTextContent("recipe_provider_failure").isError(true).build();
                }
            });
        }
        var server=builder.build();
        try { eof.await(); } finally { server.close(); }
    }
    private static String description(String name) {
        return switch(name) {
            case "search_recipes" -> "搜索本地示例食谱，required 全满足、excluded 全排除，available 仅排序。";
            case "get_recipe" -> "读取指定食谱的记录做法与来源，禁用食材命中时拒绝。";
            default -> "查询仅适用于指定食谱的有方向替换规则，无规则时返回空列表。";
        };
    }
    private static Map<String,Object> schema(String name) {
        var properties=new LinkedHashMap<String,Object>();
        var text=Map.of("type","string","minLength",1,"maxLength",500);
        var ingredients=Map.of("type","array","maxItems",64,"items",Map.of("type","string","minLength",1,"maxLength",64));
        properties.put("excludedIngredients",ingredients);
        if(name.equals("search_recipes")) {
            properties.put("availableIngredients",ingredients); properties.put("requiredIngredients",ingredients);
            properties.put("query",Map.of("type","string","maxLength",500));
            properties.put("limit",Map.of("type","integer","minimum",1,"maximum",5));
        } else {
            properties.put("recipeId",text);
            if(name.equals("suggest_substitutions")) { properties.put("ingredient",text); properties.put("availableIngredients",ingredients); }
        }
        var required=name.equals("search_recipes")?List.of():name.equals("get_recipe")?List.of("recipeId"):List.of("recipeId","ingredient");
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
}
