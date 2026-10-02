package com.diet.service.video;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties("diet.video-mcp")
public class VideoMcpProperties {
    private boolean enabled;
    private String provider = "disabled";
    private Duration timeout = Duration.ofSeconds(3);
    private Duration initializationTimeout = Duration.ofSeconds(30);
    private int defaultLimit = 3;
    private String serverName = "bilibili";
    private String command = defaultCommand();
    private List<String> arguments = List.of("-y", "@wangshunnn/bilibili-mcp-server@0.0.7");
    private String searchTool = "search_videos";
    private String querySuffix = " 做法 教程";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getInitializationTimeout() { return initializationTimeout; }
    public void setInitializationTimeout(Duration initializationTimeout) { this.initializationTimeout = initializationTimeout; }
    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public List<String> getArguments() { return arguments; }
    public void setArguments(List<String> arguments) { this.arguments = arguments == null ? List.of() : List.copyOf(arguments); }
    public String getSearchTool() { return searchTool; }
    public void setSearchTool(String searchTool) { this.searchTool = searchTool; }
    public String getQuerySuffix() { return querySuffix; }
    public void setQuerySuffix(String querySuffix) { this.querySuffix = querySuffix; }

    private static String defaultCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "npx.cmd" : "npx";
    }
}
