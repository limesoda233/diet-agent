package com.diet.controller.memory;

import com.diet.auth.AuthPrincipal;
import com.diet.service.memory.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/diet/memories")
public class MemoryController {
    private final MemoryStore store;
    private final boolean enabled;
    public MemoryController(MemoryStore store,@Value("${diet.memory.enabled:false}") boolean enabled) {this.store=store;this.enabled=enabled;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return listForUser(principal.userId());
    }
    private Map<String,Object> listForUser(long user) {
        checkUser(user);
        if(!enabled) return Map.of("enabled",false,"collectionEnabled",false,"items",List.of());
        return Map.of("enabled",true,"collectionEnabled",store.collectionEnabled(user),"items",store.list(user));
    }
    @PutMapping("/settings") public Map<String,Object> settings(@AuthenticationPrincipal AuthPrincipal principal,@RequestBody JsonNode body) {
        long user = principal.userId();
        checkEnabled(user); fields(body,Set.of("collectionEnabled"));
        if(body.size()!=1 || !body.path("collectionEnabled").isBoolean())throw new IllegalArgumentException("请提供collectionEnabled布尔值");
        store.setCollection(user,body.path("collectionEnabled").booleanValue());return listForUser(user);
    }
    @PatchMapping("/{id}") public MemoryItem change(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id,@RequestBody JsonNode body) {
        long user = principal.userId();
        checkEnabled(user);fields(body,Set.of("version","action","value","stance"));
        if(!body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong() || body.path("version").longValue()<1
                || !body.path("action").isTextual())throw new IllegalArgumentException("请提供有效版本和操作");
        return store.change(user,id,body.path("version").longValue(),body.path("action").textValue(),
                body.path("value").isTextual()?body.path("value").textValue():null,
                body.path("stance").isTextual()?body.path("stance").textValue():null);
    }
    private void fields(JsonNode body,Set<String> allowed) {
        if(body==null || !body.isObject())throw new IllegalArgumentException("请求必须为对象");
        body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("未知字段："+k);});
    }
    private void checkUser(long user) {if(user<=0)throw new IllegalArgumentException("用户编号无效");}
    private void checkEnabled(long user) {checkUser(user);if(!enabled)throw new IllegalArgumentException("长期记忆尚未启用，请先完成数据库迁移和配置");}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error) {return Map.of("message",error.getMessage());}
}
