package com.diet.service.orchestrator;

import com.diet.service.meal.constraint.MealConstraintContext;

import com.diet.service.meal.constraint.ConstraintParser;
import com.diet.service.meal.constraint.IngredientCatalog;

import com.diet.exception.DietException;
import com.diet.service.risk.RiskGuardService;
import com.diet.enums.ClarifyAction;
import com.diet.model.ClarifyResult;
import com.diet.model.RiskGuardResult;
import com.diet.enums.Intent;
import com.diet.model.IntentResult;
import com.diet.model.MealItem;
import com.diet.model.MealPlanBlock;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.model.ChatRequest;
import com.diet.model.ChatResponse;
import com.diet.model.RecommendResult;
import com.diet.model.ResponseResult;
import com.diet.enums.SessionPhase;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.meal.MealPlanService;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.meal.MealService;
import com.diet.service.meal.intake.MealIntakeWorkflow;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.session.SessionService;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.recipe.RecipeQueryService;
import com.diet.service.recipe.RecipeConversationContext;
import com.diet.service.recipe.RecipeConversationResolver;
import com.diet.service.recipe.RecipeVideoCoordinator;
import com.diet.service.recipe.RecipeVideoCoordinator.BranchOutcome;
import com.diet.service.recipe.RecipeVideoCoordinator.BranchStatus;
import com.diet.service.video.CookingVideo;
import com.diet.service.video.CookingVideoSearchService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 饮食推荐多 Agent 编排服务（Orchestrator）。
 * <p>
 * 一轮 {@link #dietChat} 的主链路：加载会话 → Trace → 同会话锁 → 保存消息 → 路由 → 响应落库。
 * <p>路由并不总调用模型：明确纯视频或菜谱规则可直接进入业务分支；未命中再用意图模型。
 * 菜谱请求先解析多轮指代，确认对象后共享查询启动 RAG 与视频。餐食推荐仍只用 MySQL。
 * Orchestrator 是会话状态写入者，子服务返回结果，不自行覆盖会话的展示顺序或选中菜品。</p>
 */
@Service
public class DietOrchestratorService {
    private com.diet.service.meal.planning.runtime.MealPlanningChatService mealPlanningChatService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMealPlanningChatService(com.diet.service.meal.planning.runtime.MealPlanningChatService service) {this.mealPlanningChatService=service;}
    private com.diet.service.memory.MemoryService memoryService;
    private com.diet.service.memory.MemoryPolicy memoryPolicy;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMemoryService(com.diet.service.memory.MemoryService service) { this.memoryService=service; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMemoryPolicy(com.diet.service.memory.MemoryPolicy policy) { this.memoryPolicy=policy; }
    private final RecipeConversationResolver recipeConversationResolver = new RecipeConversationResolver();

    /**
     * 闲聊时的固定引导文案，不额外调用 LLM。
     */
    private static final String CHITCHAT_REPLY = "我是小狗，负责帮你琢磨今天吃什么。告诉我餐次和口味就能开工；让我对着空碗猜，可有点为难呀。";

    /**
     * 会话消息落库服务，写入 diet_messages 表。
     */
    private final SessionService sessionService;

    /**
     * 会话状态服务，读写 phase、slots、lastRecommendations 等到 diet_sessions 表。
     */
    private final SessionStateService sessionStateService;

    /**
     * 意图识别 Agent 服务，调用 LLM 识别 intent + slots。
     */
    private final IntentAgentService intentAgentService;

    /**
     * 意图矫正规则服务，用历史状态二次修正 LLM 输出。
     */
    private final IntentReviseService intentReviseService;

    /**
     * 槽位合并服务，多轮对话中合并历史槽位与本轮槽位。
     */
    private final SlotMergeService slotMergeService;

    /**
     * 澄清 Agent 服务，槽位不足时生成追问文案。
     */
    private final ClarifyAgentService clarifyAgentService;

    /**
     * 餐食检索服务，按 sourceMode + slots 从 DB 召回候选。
     */
    private final MealSearchService mealSearchService;

    /**
     * 餐食重排服务，对候选按槽位命中二次打分排序。
     */
    private final MealRankService mealRankService;

    /**
     * 三餐规划服务，分别检索早餐、午餐和晚餐。
     */
    private final MealPlanService mealPlanService;

    /**
     * 推荐应答 Agent 服务，一次 LLM 调用生成推荐理由 + 口语回复。
     */
    private final RecommendResponseAgentService recommendResponseAgentService;

    /**
     * 餐食服务，用于 PERSONAL 模式空库前置检查。
     */
    private final MealService mealService;

    /**
     * 健康风险守卫，拦截医疗承诺/极端节食等高风险表述。
     */
    private final RiskGuardService riskGuardService;

    /**
     * 链路追踪服务，记录状态机事件和 Agent 调用到 agent_traces 表。
     */
    private final AgentTraceService agentTraceService;
    private final ConstraintParser constraintParser;
    private RecipeQueryService recipeQueryService;
    private RecipeVideoCoordinator recipeVideoCoordinator;
    private MealIntakeWorkflow mealIntakeWorkflow;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMealIntakeWorkflow(MealIntakeWorkflow workflow) {
        this.mealIntakeWorkflow = workflow;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecipeVideoCoordinator(RecipeVideoCoordinator coordinator) {
        this.recipeVideoCoordinator = coordinator;
    }
    private CookingVideoSearchService cookingVideoSearchService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecipeQueryService(RecipeQueryService recipeQueryService) {
        this.recipeQueryService = recipeQueryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCookingVideoSearchService(CookingVideoSearchService cookingVideoSearchService) {
        this.cookingVideoSearchService = cookingVideoSearchService;
    }

    /**
     * 会话级锁 Map，key=sessionId，value=锁对象，保证同 session 串行写状态。
     */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Spring 构造器注入全部依赖。
     */
    public DietOrchestratorService(
            SessionService sessionService,
            SessionStateService sessionStateService,
            IntentAgentService intentAgentService,
            IntentReviseService intentReviseService,
            SlotMergeService slotMergeService,
            ClarifyAgentService clarifyAgentService,
            MealSearchService mealSearchService,
            MealRankService mealRankService,
            MealPlanService mealPlanService,
            RecommendResponseAgentService recommendResponseAgentService,
            MealService mealService,
            RiskGuardService riskGuardService,
            AgentTraceService agentTraceService,
            ConstraintParser constraintParser
    ) {
        this.sessionService = sessionService;                           // 注入消息落库服务
        this.sessionStateService = sessionStateService;                 // 注入会话状态服务
        this.intentAgentService = intentAgentService;                   // 注入意图识别服务
        this.intentReviseService = intentReviseService;                 // 注入意图矫正服务
        this.slotMergeService = slotMergeService;                       // 注入槽位合并服务
        this.clarifyAgentService = clarifyAgentService;                 // 注入澄清 Agent 服务
        this.mealSearchService = mealSearchService;                     // 注入餐食检索服务
        this.mealRankService = mealRankService;                         // 注入餐食重排服务
        this.mealPlanService = mealPlanService;                         // 注入三餐规划服务
        this.recommendResponseAgentService = recommendResponseAgentService; // 注入推荐应答 Agent 服务
        this.mealService = mealService;                                 // 注入餐食服务
        this.riskGuardService = riskGuardService;             // 注入健康守卫
        this.agentTraceService = agentTraceService;                     // 注入链路追踪服务
        this.constraintParser = constraintParser;
    }

    /**
     * 同步处理一轮用户输入并返回完整推荐结果（HTTP 入口对应方法）。
     */
    public ChatResponse dietChat(Long userId, ChatRequest request) {
        // 生成本轮唯一 traceId，格式 trace_<32位hex>，贯穿整轮请求的所有 Trace 事件
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        // 校验 request 非空且 message 非空白，否则抛业务异常
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new DietException("用户问题不能为空");
        }
        // 校验 sourceMode 必填（PERSONAL 个人库 / PUBLIC 公共库），否则无法检索
        if (request.sourceMode() == null) {
            throw new DietException("sourceMode 不能为空，请选择 PERSONAL 或 PUBLIC");
        }

        // 从 DB 加载已有会话状态，或按 sessionId/userId 创建新会话，得到 slots/phase/lastRecommendations 等
        boolean draftAction = request.context() != null && request.context().containsKey("mealDraftAction");
        if (draftAction) {
            Object raw = request.context().get("mealDraftAction");
            if (request.sessionId() == null || request.sessionId().isBlank()
                    || !(raw instanceof Map<?,?> action)
                    || !(action.get("draftId") instanceof String id) || id.isBlank()
                    || !(action.get("action") instanceof String kind)
                    || !Set.of("SAVE", "CANCEL", "MERGE").contains(kind)) {
                throw new DietException("草稿操作无效，请使用当前草稿的确认保存或取消按钮。");
            }
        }
        SessionState initialState = draftAction
                ? sessionStateService.loadExisting(request.sessionId(), userId, request.sourceMode())
                : sessionStateService.loadOrCreate(request.sessionId(), userId, request.sourceMode());
        if (initialState == null) throw new DietException("草稿会话不存在或不属于当前用户，请重新录入。");

        // 开启 Trace 上下文；try-with-resources 结束时 TraceScope#close 会将整轮事件写入 agent_traces 表
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, initialState.sessionId(), userId)) {
            try {
                // 记录请求开始时间（纳秒），用于最后计算整轮耗时
                long startedAt = System.nanoTime();
                // Trace 事件：REQUEST_RECEIVED | 阶段 HTTP | 输入=ChatRequest | 输出=初始 SessionState
                agentTraceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);

                // 获取或创建该 sessionId 对应的锁对象，保证同一 session 并发请求串行执行
                Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                synchronized (lock) {
                    // 在锁内执行完整状态机，处理本轮用户输入
                    SessionState currentState = draftAction
                            ? sessionStateService.loadExisting(initialState.sessionId(), userId, request.sourceMode())
                            : sessionStateService.loadOrCreate(initialState.sessionId(), userId, request.sourceMode());
                    if (currentState == null) throw new DietException("草稿会话不存在或不属于当前用户，请重新录入。");
                    ChatResponse response = handleTurn(userId, request, traceId, currentState);
                    // Trace 事件：REQUEST_FINISHED | 阶段 HTTP | 输入=ChatRequest | 输出=ChatResponse | 耗时 ms
                    agentTraceService.recordEvent("REQUEST_FINISHED", "HTTP", request, response, elapsedMs(startedAt));
                    // 将最终响应返回给 Controller
                    return response;
                }
            } catch (RuntimeException error) {
                // Trace 事件：REQUEST_FAILED | 阶段 HTTP | 输入=ChatRequest | 记录异常并将 Trace 标记 FAILED
                agentTraceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                // 继续向上抛出，由全局异常处理器返回错误响应
                throw error;
            }
        }
    }

    /**
     * 在会话锁内执行本轮状态机：保存消息 → 显式/规则路由 → 必要时模型识别 → 业务分发。
     */
    private ChatResponse handleTurn(Long userId, ChatRequest request, String traceId, SessionState state) {
        if(memoryService==null) return handlePreparedTurn(userId,request,traceId,state,false);
        com.diet.service.memory.MemoryTurn turn;
        try {
            turn=memoryService.prepare(userId,state.sessionId(),request.message(),traceId);
        } catch(RuntimeException unavailable) {
            agentTraceService.recordEvent("MEMORY_UNAVAILABLE","MEMORY",null,Map.of("errorType",unavailable.getClass().getSimpleName()));
            return completeAsk(state.sessionId(),traceId,state,ClarifyResult.ask("用户记忆暂时不可用，无法确认你的饮食限制，请稍后重试。",List.of("memory")));
        }
        agentTraceService.recordEvent("MEMORY_PREPARED","MEMORY",null,
                Map.of("candidateCount",turn.facts().size(),"memoryCount",turn.items().size(),"needsClarification",turn.requiresClarification()));
        ChatResponse response;
        if(turn.hasAllergy()) {
            response=completeTextOnly(state.sessionId(),traceId,state,Intent.CLARIFY_NEEDED,
                    ResponseResult.textOnly(com.diet.service.memory.MemoryPolicy.ALLERGY_LIMIT));
        } else if(turn.requiresClarification()) {
            response=completeAsk(state.sessionId(),traceId,state,ClarifyResult.ask("我未能可靠识别你提到的禁忌，请明确需要排除的食材或过敏原。",List.of("dietaryRestrictions")));
        } else {
            // Temporary ingredient restrictions must also reach rule-routed recipe queries.
            var effectiveRequest=withMemoryRecipeContext(request,turn);
            response=handlePreparedTurn(userId,effectiveRequest,traceId,state,true);
        }
        var notices=new java.util.ArrayList<>(turn.notices());
        if(turn.items().stream().anyMatch(i->"ACTIVE".equals(i.status()) && Set.of("EQUIPMENT","MAX_MINUTES").contains(i.type())))
            notices.add("已记录烹饪条件；当前数据尚不能完整核验厨具和耗时，结果不保证满足这些条件。");
        response.memoryNotices(List.copyOf(notices));
        try {memoryService.recordResponse(userId,state.sessionId(),traceId,response);}
        catch(RuntimeException failed) {agentTraceService.recordEvent("MEMORY_EXPOSURE_FAILED","MEMORY",null,Map.of("errorType",failed.getClass().getSimpleName()));}
        return response;
    }

    private ChatRequest withMemoryRecipeContext(ChatRequest request,com.diet.service.memory.MemoryTurn turn) {
        return com.diet.service.memory.MemoryRequestContext.prepare(request,turn,
                recipeQueryService!=null && recipeQueryService.matches(request.message(),request.context()));
    }

    private ChatResponse handlePreparedTurn(Long userId, ChatRequest request, String traceId, SessionState state, boolean messageRecorded) {
        // 从会话状态中取出 sessionId，后续落库和 Agent 调用都依赖它
        String sessionId = state.sessionId();
        // 从会话状态中取出数据源模式（PERSONAL / PUBLIC）
        SourceMode sourceMode = state.sourceMode();

        // 将用户消息 INSERT 到 diet_messages 表，role=user，intent=null，关联 traceId
        if(!messageRecorded) sessionService.appendMessage(sessionId, "user", request.message(), null, traceId);

        // Trace 事件：USER_MESSAGE_RECORDED | 阶段 SESSION | 输入=用户原文 | 输出=sessionId+sourceMode
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", request.message(), Map.of("sessionId", sessionId, "sourceMode", sourceMode));

        if (mealIntakeWorkflow != null) {
            var intake = mealIntakeWorkflow.handle(userId, request, state);
            if (intake.isPresent()) {
                var result = intake.get();
                if (!result.persisted()) sessionStateService.save(result.state());
                sessionService.appendMessage(sessionId, "assistant", result.speechText(), null, traceId);
                return ChatResponse.mealDraft(sessionId, traceId, result.speechText(), result.state().mealDraft());
            }
        }

        if(mealPlanningChatService!=null && mealPlanningChatService.matches(request))
            return handleMultiAgentPlan(userId,request,traceId,state);

        if (cookingVideoSearchService != null && cookingVideoSearchService.isVideoOnly(request.message(), request.context())) {
            if(memoryService!=null && !com.diet.service.memory.MemoryRequestContext.exclusions(request.context()).isEmpty())
                return completeTextOnly(sessionId,traceId,state,Intent.RECIPE_QUERY,
                        ResponseResult.textOnly("视频缺少可核验的配料信息，无法确认满足你的忌口，因此暂不返回视频。你可以查询带食材证据的文字菜谱。"));
            CookingVideoSearchService.VideoSearchRequest videoRequest;
            try {
                videoRequest = cookingVideoSearchService.route(request.message(), request.context());
            } catch (IllegalArgumentException invalid) {
                return completeAsk(sessionId, traceId, state,
                        ClarifyResult.ask("视频搜索参数无效，请检查搜索关键词、平台和数量。", List.of("videoSearch")));
            }
            List<CookingVideo> videos;
            try {
                videos = cookingVideoSearchService.search(videoRequest).block();
            } catch (RuntimeException unavailable) {
                agentTraceService.recordEvent("COOKING_VIDEO_SEARCH_FAILED", "VIDEO_MCP", null,
                        Map.of("status", BranchStatus.FAILED, "errorType", sanitizedErrorType(unavailable.getClass().getSimpleName())));
                return completeTextOnly(sessionId, traceId, state, Intent.RECIPE_QUERY,
                        ResponseResult.textOnly("视频搜索暂时不可用，文字菜谱仍可正常查询。"));
            }
            String text = cookingVideoSearchService.format(videos);
            agentTraceService.recordEvent("COOKING_VIDEO_SEARCHED", "VIDEO_MCP", videoRequest,
                    Map.of("resultCount", videos == null ? 0 : videos.size()));
            return completeVideoSearch(sessionId, traceId, state, text, videos);
        }

        if (recipeQueryService != null && (recipeQueryService.matches(request.message(), request.context())
                || recipeConversationResolver.isSelectionReply(request.message(), state.recipeConversation()))) {
            agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", request.message(),
                    Map.of("route", Intent.RECIPE_QUERY, "source", "recipe_rule"));
            return handleRecipeQuery(sessionId, userId, request, traceId, state, false);
        }

        // PERSONAL 模式且用户尚未录入任何个人餐食时，提前返回引导文案，跳过后续检索
        if (sourceMode == SourceMode.PERSONAL && !mealService.hasPersonalMeals(userId)) {
            // 构造纯文本响应，提示用户先录入菜单
            ResponseResult response = ResponseResult.textOnly("你还没有录入个人餐食数据。可以先添加几道常吃的食堂菜，再让我按你的饭堂菜单推荐。");
            // Trace 事件：PERSONAL_LIBRARY_EMPTY | 阶段 ROUTE | 输入=userId | 输出=引导文案
            agentTraceService.recordEvent("PERSONAL_LIBRARY_EMPTY", "ROUTE", Map.of("userId", userId), response);
            // 走纯文本完成分支：保存状态 + 落库助手消息 + 返回 ChatResponse
            return completeTextOnly(sessionId, traceId, state, Intent.MEAL_RECOMMENDATION, response);
        }

        // 意图识别：调用 IntentAgent：传入 sessionId、userId、用户原文、历史槽位、最近 3 条对话摘要
        IntentResult rawIntent = intentAgentService.recognize(sessionId, userId, request.message(), state.slots(), sessionService.recentConversationTurns(sessionId, userId, 3));
        // Trace 事件：INTENT_RECOGNIZED | 阶段 INTENT | 输入=用户原文 | 输出=IntentResult（intent/slots/confidence）
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", request.message(), rawIntent);

        // 调用 IntentReviseService，结合历史 phase/slots/lastRecommendations 二次矫正意图
        IntentResult intent = intentReviseService.revise(state, rawIntent, request.message());
        // Trace 事件：INTENT_REVISED | 阶段 INTENT | 输入=矫正前 rawIntent | 输出=矫正后 intent
        agentTraceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);

        if(mealPlanningChatService!=null && intent.intent()==Intent.MEAL_PLAN)
            return handleMultiAgentPlan(userId,request,traceId,state);

        if (intent.intent() != Intent.HEALTH_RISK
                && intent.intent() != Intent.OTHER
                && intent.intent() != Intent.RECIPE_QUERY) {
            try {
                state = state.withMealConstraintContext(constraintParser.resolveContext(request.message(), state.mealConstraintContext(), request.context(),
                        intent.intent() == Intent.MEAL_ADJUST || state.phase() == SessionPhase.CLARIFY));
                agentTraceService.recordEvent("RETRIEVAL_CONSTRAINTS_RESOLVED", "RETRIEVAL", request.message(), state.mealConstraintContext());
            } catch (IllegalArgumentException invalid) {
                if(state.mealConstraintContext().pendingConstraintText().isBlank()) state=state.withMealConstraintContext(state.mealConstraintContext().pending(request.message()));
                return completeAsk(sessionId, traceId, state, ClarifyResult.ask(invalid.getMessage(), List.of("retrievalConstraints")));
            }
        }

        // Trace 事件：ROUTE_SELECTED | 阶段 ROUTE | 输入=最终 intent | 输出=路由目标 intent 枚举名
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("route", intent.intent()));

        // 按最终意图枚举分发到对应分支处理器
        try {
        List<String> currentExclusions=memoryService==null?List.of():
                com.diet.service.memory.MemoryRequestContext.exclusions(request.context());
        return switch (intent.intent()) {
            // 推荐或需澄清：走推荐主链路（澄清由 ClarifyAgent 内部决定）
            case MEAL_RECOMMENDATION, CLARIFY_NEEDED ->
                    handleRecommendation(sessionId, userId, request.message(), traceId, state, intent,currentExclusions);
            // 调整上轮推荐：排除已推荐 ID，重跑推荐流水线
            case MEAL_ADJUST -> handleAdjust(sessionId, userId, request.message(), traceId, state, intent,currentExclusions);
            // 多餐规划：标记 PLAN 阶段后走推荐流水线
            case MEAL_PLAN -> mealPlanningChatService==null?handlePlan(sessionId, userId, request.message(), traceId, state, intent,currentExclusions):handleMultiAgentPlan(userId,request,traceId,state);
            // 菜谱查询：意图模型兜底识别后强制构造 recipeQuery，进入公共菜谱 RAG。
            case RECIPE_QUERY -> handleRecipeQuery(sessionId, userId, request, traceId, state, true);
            // 健康风险：返回 NutritionGuard 保守提示，不走推荐
            case HEALTH_RISK -> handleHealthRisk(sessionId, traceId, state);
            // 其他无关饮食的内容：返回固定引导文案
            case OTHER -> handleChitchat(sessionId, traceId, state);
        };
        } catch (IllegalArgumentException invalid) {
            return completeAsk(sessionId, traceId, state, ClarifyResult.ask(invalid.getMessage(), List.of("retrievalConstraints")));
        }
    }

    private ChatResponse handleMultiAgentPlan(Long userId,ChatRequest request,String traceId,SessionState state) {
        if(intentReviseService.containsHealthRiskKeyword(request.message()))
            return handleHealthRisk(state.sessionId(),traceId,state);
        try {
            String constraintText=request.message().replaceAll("(?:尽量)?(?:不要|不能|不许|避免|不)?重复","")
                    .replaceAll("(?:只要|只能)(早餐|午餐|晚餐)","$1");
            var context=constraintParser.resolveContext(constraintText,state.mealConstraintContext(),request.context(),false);
            var working=state.withPhase(SessionPhase.PLAN).withIntent(Intent.MEAL_PLAN).withMealConstraintContext(context);
            List<String> exclusions=memoryService==null?List.of():com.diet.service.memory.MemoryRequestContext.exclusions(request.context());
            var frozenInput=working.withMealConstraintContext(new MealConstraintContext(context.queryText(),withCurrentExclusions(context.constraints(),exclusions),context.source(),context.pendingConstraintText()));
            var response=mealPlanningChatService.plan(userId,request,frozenInput,traceId);
            var ids=response.displayBlocks().stream().map(com.diet.model.MealResponse::id).toList();
            var saved=working.appendLastRecommendations(ids).withRecipeConversation(RecipeConversationContext.displayed(response.displayBlocks()));
            sessionStateService.save(saved);
            sessionService.appendMessage(state.sessionId(),"assistant",response.speechText(),Intent.MEAL_PLAN.name(),traceId);
            agentTraceService.recordEvent("RESPONSE_READY","PLAN",null,response);
            return response;
        } catch(IllegalArgumentException invalid) {
            return completeAsk(state.sessionId(),traceId,state,ClarifyResult.ask(invalid.getMessage(),List.of("mealPlan")));
        }
    }

    private ChatResponse handleRecipeQuery(String sessionId,
                                           Long userId,
                                           ChatRequest request,
                                           String traceId,
                                           SessionState state,
                                           boolean forceRoute) {
        if (recipeQueryService == null) {
            return completeTextOnly(sessionId, traceId, state, Intent.RECIPE_QUERY,
                    ResponseResult.textOnly("菜谱检索功能当前未启用。"));
        }
        Map<String, Object> context = request.context();
        boolean explicitTarget = context != null && context.get("recipeQuery") instanceof Map<?, ?> target
                && target.get("mealId") != null;
        String referenceText = context != null && context.get("recipeQuery") instanceof Map<?, ?> target
                && target.get("query") instanceof String value && !value.isBlank() ? value : request.message();
        // 个人 mealId 是明确的业务对象，后续必须校验归属；其它文本先解析“第二道/怎么做”。
        // 澄清在两个并行分支启动前完成，避免拿一个没有菜名的问题去搜。
        if (!explicitTarget) {
            var resolution = recipeConversationResolver.resolve(referenceText, state.recipeConversation());
            agentTraceService.recordEvent("RECIPE_REFERENCE_RESOLVED", "RECIPE",
                    Map.of("originalQuery", request.message()), resolution);
            if (!resolution.clarification().isBlank()) {
                return completeAsk(sessionId, traceId,
                        state.withRecipeConversation(state.recipeConversation().pending()),
                        ClarifyResult.ask(resolution.clarification(), List.of("recipeDish")));
            }
            state = state.withRecipeConversation(state.recipeConversation().selected(resolution.selectedDish()));
            if (!resolution.query().equals(referenceText)) {
                Map<String, Object> resolvedContext = new LinkedHashMap<>();
                if (context != null) resolvedContext.putAll(context);
                Map<String, Object> query = new LinkedHashMap<>();
                if (context != null && context.get("recipeQuery") instanceof Map<?, ?> supplied) {
                    supplied.forEach((key, value) -> query.put(String.valueOf(key), value));
                }
                query.put("query", resolution.query());
                resolvedContext.put("recipeQuery", query);
                context = resolvedContext;
            }
        } else {
            // Avoid carrying an old dish into followups of an explicit new target.
            state = state.withRecipeConversation(state.recipeConversation().selected(""));
        }
        if (forceRoute && !recipeQueryService.matches(request.message(), context)) {
            Map<String, Object> forcedContext = new LinkedHashMap<>();
            if (context != null) forcedContext.putAll(context);
            forcedContext.put("recipeQuery", Map.of("query", request.message()));
            context = forcedContext;
        }
        final Map<String, Object> finalContext = memoryService==null?context:
                com.diet.service.memory.MemoryRequestContext.mergeRecipe(context,request.message());
        return executeRecipeQuery(sessionId, userId, request.message(), finalContext, traceId, state);
    }

    private ChatResponse executeRecipeQuery(String sessionId,
                                            Long userId,
                                            String message,
                                            Map<String, Object> finalContext,
                                            String traceId,
                                            SessionState state) {
        if (recipeVideoCoordinator == null || (memoryService!=null
                && !com.diet.service.memory.MemoryRequestContext.exclusions(finalContext).isEmpty())) {
            try {
                var answer = recipeQueryService.answer(sessionId, userId, message, finalContext);
                agentTraceService.recordEvent("RECIPE_ANSWER_READY", "RECIPE_RAG", null,
                        recipeAnswerTrace(answer));
                ChatResponse response = completeRecipeResponse(sessionId, traceId, state, answer.text(), List.of());
                agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", null, response);
                return response;
            } catch (IllegalArgumentException invalid) {
                return completeAsk(sessionId, traceId, state,
                        ClarifyResult.ask(invalid.getMessage(), List.of("recipeQuery")));
            }
        }

        com.diet.service.recipe.RecipeQueryService.PreparedRecipeQuery prepared;
        CookingVideoSearchService.VideoSearchRequest videoRequest;
        try {
            prepared = recipeQueryService.prepareQuery(userId, message, finalContext);
            videoRequest = recipeVideoCoordinator.recipeVideoRequest(prepared.query().text());
        } catch (IllegalArgumentException invalid) {
            return completeAsk(sessionId, traceId, state,
                    ClarifyResult.ask("菜谱查询参数无效，请检查菜名和食材限制。", List.of("recipeQuery")));
        } catch (RuntimeException unavailable) {
            agentTraceService.recordEvent("RECIPE_QUERY_PREPARATION_FAILED", "RECIPE", null,
                    Map.of("status", BranchStatus.FAILED,
                            "errorType", sanitizedErrorType(unavailable.getClass().getSimpleName())));
            return completeRecipeResponse(sessionId, traceId, state,
                    "菜谱和视频查询暂时不可用，请稍后再试。", List.of());
        }
        if (state.recipeConversation().activeDish().isBlank()) {
            var selected = recipeConversationResolver.resolve(prepared.query().text(), RecipeConversationContext.empty());
            state = state.withRecipeConversation(state.recipeConversation().selected(selected.selectedDish()));
        }
        agentTraceService.recordEvent("RECIPE_QUERY_NORMALIZED", "RECIPE", null,
                Map.of("originalText", message, "recipeQuery", prepared.query().text(),
                        "videoQuery", videoRequest.query(), "source", prepared.source()));
        agentTraceService.recordEvent("RECIPE_RAG_STARTED", "RECIPE_RAG", null, Map.of());
        agentTraceService.recordEvent("VIDEO_MCP_STARTED", "VIDEO_MCP", null, Map.of());
        var result = recipeVideoCoordinator.executePrepared(
                agentTraceService.propagate(
                        () -> recipeQueryService.answer(sessionId, prepared)), videoRequest).block();
        var recipe = result.recipe();
        var video = result.video();
        boolean grounded = recipe.status() == BranchStatus.SUCCESS && recipe.value() != null;
        List<CookingVideo> videos = video.status() == BranchStatus.SUCCESS && video.value() != null
                ? video.value() : List.of();
        int citations = recipe.value() == null ? 0 : recipe.value().citations().size();
        String recipeEvent = switch (recipe.status()) {
            case SUCCESS -> "RECIPE_RAG_COMPLETED";
            case REFUSED -> "RECIPE_RAG_REFUSED";
            default -> "RECIPE_RAG_FAILED";
        };
        String videoEvent = switch (video.status()) {
            case SUCCESS -> "VIDEO_MCP_COMPLETED";
            case EMPTY -> "VIDEO_MCP_EMPTY";
            case TIMEOUT -> "VIDEO_MCP_TIMEOUT";
            default -> "VIDEO_MCP_FAILED";
        };
        Map<String, Object> recipeTrace = new LinkedHashMap<>(branchTrace(recipe, "citationCount", citations));
        if (recipe.value() != null) recipeTrace.putAll(recipeAnswerTrace(recipe.value()));
        agentTraceService.recordEvent(recipeEvent, "RECIPE_RAG", null, recipeTrace);
        agentTraceService.recordEvent(videoEvent, "VIDEO_MCP", null,
                branchTrace(video, "videoCount", videos.size()));

        String text;
        if (grounded) {
            text = recipe.value().text();
        } else if (recipe.status() == BranchStatus.REFUSED) {
            text = videos.isEmpty() ? "未找到可信菜谱或教学视频，请尝试换一个菜名。"
                    : recipe.value() == null || recipe.value().text().isBlank()
                    ? "公共语料暂无可信菜谱，以下是可参考的教学视频。" : recipe.value().text();
        } else {
            text = videos.isEmpty() ? "菜谱和视频查询暂时不可用，请稍后再试。"
                    : "暂无可信文字菜谱，以下是可参考的教学视频。";
        }
        ChatResponse response = completeRecipeResponse(sessionId, traceId, state, text, videos);
        if (!grounded && !videos.isEmpty()) response.responseType("VIDEOS");
        agentTraceService.recordEvent("RECIPE_RESPONSE_COMPOSED", "RECIPE", null,
                Map.of("recipeStatus", recipe.status(), "videoStatus", video.status(),
                        "recipeDurationMs", recipe.durationMs(), "videoDurationMs", video.durationMs(),
                        "citationCount", citations, "videoCount", videos.size(),
                        "degraded", !grounded || video.status() != BranchStatus.SUCCESS,
                        "responseType", response.responseType()));
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", null, response);
        return response;
    }

    private Map<String, Object> recipeAnswerTrace(com.diet.service.recipe.rag.model.RecipeAnswer answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refused", answer.refused());
        payload.put("citationCount", answer.citations().size());
        for (String key : List.of("answerPurpose", "generation")) {
            if (answer.diagnostics().get(key) instanceof String value) payload.put(key, value);
        }
        return payload;
    }

    private Map<String, Object> branchTrace(BranchOutcome<?> outcome, String countKey, int count) {
        return Map.of("status", outcome.status(), "durationMs", outcome.durationMs(), countKey, count,
                "errorType", sanitizedErrorType(outcome.errorType()));
    }

    private String sanitizedErrorType(String errorType) {
        if (errorType == null || errorType.isBlank()) return "";
        return errorType.matches("[A-Za-z][A-Za-z0-9]{0,63}(Exception|Error)") ? errorType : "UnknownError";
    }

    private ChatResponse completeRecipeResponse(String sessionId,
                                                String traceId,
                                                SessionState state,
                                                String text,
                                                List<CookingVideo> videos) {
        sessionStateService.save(state.withIntent(Intent.RECIPE_QUERY));
        sessionService.appendMessage(sessionId, "assistant", text, Intent.RECIPE_QUERY.name(), traceId);
        return videos.isEmpty() ? ChatResponse.answer(sessionId, traceId, text, List.of(), "WAIT_USER")
                : ChatResponse.recipe(sessionId, traceId, text, videos);
    }

    /**
     * 推荐主链路：合并槽位 → ClarifyAgent 判追问 → 槽位足够则进入 completeRecommendation。
     */
    private ChatResponse handleRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent,List<String> currentExclusions) {
        // 将历史 slots 与 IntentAgent 本轮识别的 slots 合并（本轮非空覆盖，本轮空保留历史）
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());

        // Trace 事件：SLOTS_MERGED | 阶段 SLOT | 输入=stateSlots+intentSlots | 输出=mergedSlots
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        // 基于合并槽位构建工作态：意图固定为 MEAL_RECOMMENDATION
        SessionState workingState = state.withIntent(Intent.MEAL_RECOMMENDATION).withSlots(mergedSlots);

        // 【重要】不能完全依靠agent的意图识别,在进入推荐之前,规则层面上也需要判断是否有足够的信息
        // 调用 ClarifyAgent：规则层先判缺失槽位，不足则 LLM 生成追问文案
        ClarifyResult clarify = clarifyAgentService.decide(sessionId, userInput,
                memoryPolicy==null?mergedSlots:memoryPolicy.defaults(userId,mergedSlots));
        // Trace 事件：CLARIFY_DECISION | 阶段 CLARIFY | 输入=mergedSlots | 输出=ClarifyResult（ASK/READY）
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", mergedSlots, clarify);

        // 若 ClarifyResult.action == ASK，说明槽位不足，需要追问用户
        if (clarify.action() == ClarifyAction.ASK) {
            // 直接返回追问，不进入检索推荐
            return completeAsk(sessionId, traceId, workingState, clarify);
        }
        // 槽位足够：phase 切 RECOMMEND，excludeMealIds 为空
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState.withPhase(SessionPhase.RECOMMEND), List.of(),currentExclusions);
    }

    private ChatResponse completeAsk(String sessionId, String traceId, SessionState workingState, ClarifyResult clarify) {
        // 将会话 phase 切换为 CLARIFY，表示当前处于澄清等待用户回复状态
        SessionState clarifyState = workingState.withPhase(SessionPhase.CLARIFY);
        // 将澄清态会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(clarifyState);

        // 将助手追问消息 INSERT 到 diet_messages，intent=CLARIFY_NEEDED
        sessionService.appendMessage(sessionId, "assistant", clarify.questionToAsk(), Intent.CLARIFY_NEEDED.name(), traceId);

        // 构造澄清型 ChatResponse，携带 missingSlots 供前端展示
        ChatResponse response = ChatResponse.clarify(sessionId, traceId, clarify.questionToAsk(), clarify.missingSlots());

        // Trace 事件：RESPONSE_READY | 阶段 CLARIFY | 输入=clarify | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "CLARIFY", clarify, response);

        // 直接返回追问，不进入检索推荐
        return response;
    }

    /**
     * 调整链路：合并槽位 → 取 excludeMealIds → 重跑推荐流水线。
     */
    private ChatResponse handleAdjust(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent,List<String> currentExclusions) {
        // 合并历史槽位与本轮 IntentAgent 识别的槽位
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());

        // 从会话状态取出本会话已推荐过的 mealId 列表，供换一批时累积排除
        List<Long> excludeMealIds = state.lastRecommendations() == null ? List.of() : state.lastRecommendations();

        // Trace 事件：ADJUST_CONTEXT_RESOLVED | 阶段 ADJUST | 输入=intent | 输出=mergedSlots+excludeMealIds
        agentTraceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent, traceMap("mergedSlots", mergedSlots, "excludeMealIds", excludeMealIds));

        // 构建调整态工作会话：意图=MEAL_ADJUST，phase=RECOMMEND
        SessionState workingState = state.withIntent(Intent.MEAL_ADJUST)
                .withSlots(mergedSlots)
                .withPhase(SessionPhase.RECOMMEND);

        // 进入推荐流水线，仅排除已推荐餐食，实现换一批
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState, excludeMealIds,currentExclusions);
    }

    /**
     * 多餐规划链路：合并槽位 → 标记 PLAN 阶段 → 走推荐流水线。
     */
    private ChatResponse handlePlan(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent,List<String> currentExclusions) {
        // 合并历史槽位与本轮槽位
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        // Trace 事件：PLAN_CONTEXT_RESOLVED | 阶段 PLAN | 输入=intent | 输出=mergedSlots
        agentTraceService.recordEvent("PLAN_CONTEXT_RESOLVED", "PLAN", intent, mergedSlots);
        // 构建规划态工作会话：意图=MEAL_PLAN，phase=PLAN
        SessionState workingState = state.withIntent(Intent.MEAL_PLAN).withSlots(mergedSlots).withPhase(SessionPhase.PLAN);
        // 将“三餐”展开为早餐、午餐、晚餐，分别检索并按餐次分组返回
        return completeMealPlan(sessionId, userId, userInput, traceId, workingState,currentExclusions);
    }

    /**
     * 多餐规划收尾：三餐分别检索、合并 Guard、保存会话并返回分组结果。
     */
    private ChatResponse completeMealPlan(
            String sessionId,
            Long userId,
            String userInput,
            String traceId,
            SessionState state,List<String> currentExclusions) {
        var baseContext=state.mealConstraintContext();
        var effectiveContext=new MealConstraintContext(baseContext.queryText(),
                withCurrentExclusions(baseContext.constraints(),currentExclusions),baseContext.source(),baseContext.pendingConstraintText());
        MealPlanService.PlanResult plan = mealPlanService.buildPlan(
                sessionId, userId, userInput, state.sourceMode(),
                state.slots(), effectiveContext);

        agentTraceService.recordEvent(
                plan.recommend().recommendations().isEmpty() ? "NO_MEAL_PLAN_MATCHED" : "MEAL_PLAN_BUILT",
                "PLAN",
                state.slots(),
                traceMap("planBlocks", plan.planBlocks(), "recommendationCount", plan.recommend().recommendations().size()));

        ResponseResult response = plan.response();
        List<MealPlanBlock> planBlocks = plan.planBlocks();
        RiskGuardResult guard = riskGuardService.check(userInput, Intent.MEAL_PLAN, plan.recommend(), response);
        agentTraceService.recordEvent(
                "NUTRITION_GUARD_CHECKED",
                "GUARD",
                Map.of("intent", Intent.MEAL_PLAN, "response", response),
                guard);

        if (!guard.passed()) {
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            planBlocks = List.of();
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        List<Long> recommendationIds = plan.recommend().recommendations().stream()
                .map(option -> option.itemId())
                .toList();
        SessionState savedState = state.appendLastRecommendations(recommendationIds)
                .withRecipeConversation(RecipeConversationContext.displayed(
                        planBlocks.stream().flatMap(block -> block.meals().stream()).toList()));
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), Intent.MEAL_PLAN.name(), traceId);

        ChatResponse chatResponse = ChatResponse.plan(
                sessionId,
                traceId,
                response.speechText(),
                response.displayBlocks(),
                planBlocks,
                response.nextAction());
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    /**
     * 健康风险分支：返回 NutritionGuard 保守提示，不走推荐链路。
     */
    private ChatResponse handleHealthRisk(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 conservativeMessage 固定文案
        ResponseResult response = ResponseResult.textOnly(riskGuardService.conservativeMessage());
        // 走纯文本完成分支，intent 标记为 HEALTH_RISK
        return completeTextOnly(sessionId, traceId, state, Intent.HEALTH_RISK, response);
    }

    /**
     * 闲聊分支：返回固定引导文案，不调用 LLM。
     */
    private ChatResponse handleChitchat(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 CHITCHAT_REPLY 常量
        ResponseResult response = ResponseResult.textOnly(CHITCHAT_REPLY);
        // 走纯文本完成分支，intent 标记为 CHITCHAT
        return completeTextOnly(sessionId, traceId, state, Intent.OTHER, response);
    }

    /**
     * 完整推荐流水线：检索 → 重排 → LLM 生成理由与口语回复 → Guard 审查 → 持久化并返回。
     */
    private com.diet.service.meal.constraint.MealConstraints withCurrentExclusions(com.diet.service.meal.constraint.MealConstraints base,List<String> extra) {
        if(extra.isEmpty())return base;
        var excluded=new java.util.LinkedHashSet<>(base.excludedIngredients());
        var catalog=new IngredientCatalog();extra.stream().map(catalog::normalize).forEach(excluded::add);
        return new com.diet.service.meal.constraint.MealConstraints(base.requiredIngredients(),List.copyOf(excluded),base.requiredSlots(),base.excludedSlots());
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, List<Long> excludeMealIds,List<String> currentExclusions) {
        SlotBundle effectiveSlots=memoryPolicy==null?state.slots():memoryPolicy.defaults(userId,state.slots());
        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(
                state.sourceMode(), userId, effectiveSlots, excludeMealIds,
                withCurrentExclusions(state.mealConstraintContext().constraints(),currentExclusions)));
        // Trace 事件：MEAL_SEARCHED | 阶段 SEARCH | 输入=slots | 输出=候选数量+candidates 列表
        agentTraceService.recordEvent("MEAL_SEARCHED", "SEARCH", state.slots(), Map.of("candidateCount", candidates.size(), "candidates", candidates));

        // 构造排序请求：候选列表 + slots + excludeMealIds，返回 top10
        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, effectiveSlots, excludeMealIds));
        if(memoryPolicy!=null)ranked=memoryPolicy.rank(userId,state.slots(),ranked);
        // Trace 事件：MEAL_RANKED | 阶段 RANK | 输入=excludeMealIds | 输出=重排后数量+ranked 列表
        agentTraceService.recordEvent("MEAL_RANKED", "RANK", Map.of("excludeMealIds", excludeMealIds), Map.of("rankedCount", ranked.size(), "ranked", ranked));

        // 结果为空时，按 sourceMode 返回不同的空库提示文案
        if (ranked.isEmpty()) {
            // PERSONAL 模式提示补充饭堂菜或切 PUBLIC；PUBLIC 模式提示补充槽位
            ResponseResult empty = ResponseResult.textOnly(!state.mealConstraintContext().constraints().isEmpty()
                    ? "当前记录中没有能核对并满足这些限制的餐食。部分餐食的食材信息可能尚未补全，你可以补充记录或明确调整限制。"
                    : state.sourceMode() == SourceMode.PERSONAL
                    ? "你当前的个人餐食库里暂时没有匹配的餐食，可以补充更多饭堂菜，或者切换到公共餐食数据试试。"
                    : "公共餐食库里暂时没有很匹配的结果，你可以切换个人模式补充餐次、口味或想吃的菜系。");
            // Trace 事件：NO_MEAL_MATCHED | 阶段 RECOMMEND | 输入=state | 输出=空结果提示文案
            agentTraceService.recordEvent("NO_MEAL_MATCHED", "RECOMMEND", state, empty);
            // 走纯文本完成分支，intent 保持当前 state.currentIntent()
            return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
        }

        // 调用 RecommendResponseAgent：top3 候选 + 用户原文 + slots → 推荐理由 + speechText + 卡片
        RecommendResponseAgentService.Result merged = recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, state.sourceMode(), effectiveSlots, ranked);

        // 从结果中取出 RecommendResult（含 recommendations 列表和 needDisclaimer 标记）
        RecommendResult recommend = merged.recommend();
        // Trace 事件：RECOMMEND_RESULT_BUILT | 阶段 RECOMMEND | 输入=strategy+ranked | 输出=RecommendResult
        String strategy = state.currentIntent() == null ? Intent.MEAL_RECOMMENDATION.name() : state.currentIntent().name();
        agentTraceService.recordEvent("RECOMMEND_RESULT_BUILT", "RECOMMEND", Map.of("strategy", strategy, "ranked", ranked), recommend);

        // 从结果中取出 ResponseResult（含 speechText、displayBlocks、nextAction）
        ResponseResult response = merged.response();
        // Trace 事件：RESPONSE_AGENT_RESULT | 阶段 RESPONSE | 输入=recommend | 输出=ResponseResult
        agentTraceService.recordEvent("RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);

        // 调用 riskGuardService 检查用户输入 + 最终回复是否含健康风险关键词
        RiskGuardResult guard = riskGuardService.check(userInput, state.currentIntent(), recommend, response);
        // Trace 事件：NUTRITION_GUARD_CHECKED | 阶段 GUARD | 输入=intent+response | 输出=GuardResult（passed/reasons）
        agentTraceService.recordEvent("NUTRITION_GUARD_CHECKED", "GUARD", Map.of("intent", state.currentIntent(), "response", response), guard);

        // Guard 未通过时，用 conservativeMessage 替换 speechText，丢弃原 LLM 回复
        if (!guard.passed()) {
            // 重建纯文本 ResponseResult，内容为 guard.rewriteSuggestion()
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            // Trace 事件：NUTRITION_GUARD_REWRITTEN | 阶段 GUARD | 输入=guard | 输出=替换后的 response
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            // Guard 通过时也记录事件，表示未改写（COMPLIANCE_GUARD_REWRITTEN 为历史命名，语义=合规检查通过）
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        // 从推荐结果中提取本轮推荐的 mealId 列表，追加到 lastRecommendations 供下轮调整累积排除
        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        // 基于当前 state 累积更新 lastRecommendations 字段
        SessionState savedState = state.appendLastRecommendations(lastIds)
                .withRecipeConversation(RecipeConversationContext.displayed(response.displayBlocks()));

        // 将更新后的会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(savedState);

        // 将助手回复 INSERT 到 diet_messages，intent=当前意图名，content=speechText
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), state.currentIntent().name(), traceId);

        // 构造最终 ChatResponse：含 speechText、餐食卡片 displayBlocks、nextAction=WAIT_USER
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());

        // Trace 事件：RESPONSE_READY | 阶段 RESPONSE | 输入=savedState | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);

        // 返回带推荐卡片的完整响应
        return chatResponse;
    }

    /**
     * 纯文本分支的统一收尾：更新 intent → 保存状态 → 落库消息 → 返回 ChatResponse。
     */
    private ChatResponse completeTextOnly(String sessionId, String traceId, SessionState state, Intent intent, ResponseResult response) {
        // 将会话 currentIntent 更新为传入的 intent 枚举
        SessionState savedState = state.withIntent(intent);
        // 将更新后的会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(savedState);

        // 将助手纯文本回复 INSERT 到 diet_messages
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), intent.name(), traceId);

        // 构造 ChatResponse（无餐食卡片，displayBlocks 为空）
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());

        // Trace 事件：RESPONSE_READY | 阶段 RESPONSE | 输入=intent+savedState | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", Map.of("intent", intent, "state", savedState), chatResponse);

        // 返回纯文本响应
        return chatResponse;
    }

    private ChatResponse completeVideoSearch(String sessionId,
                                             String traceId,
                                             SessionState state,
                                             String text,
                                             List<CookingVideo> videos) {
        SessionState savedState = state.withIntent(Intent.RECIPE_QUERY);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", text, Intent.RECIPE_QUERY.name(), traceId);
        ChatResponse response = ChatResponse.videos(sessionId, traceId, text, videos);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE",
                Map.of("intent", Intent.RECIPE_QUERY, "state", savedState), response);
        return response;
    }

    /**
     * 将纳秒级开始时间戳转为毫秒耗时。
     */
    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 构造 Trace payload Map，支持 key-value 交替传入，允许 value 为 null。
     */
    private Map<String, Object> traceMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 每两个元素为一组 key-value，步长 2 遍历
        for (int i = 0; i + 1 < entries.length; i += 2) {
            // 将 key 转 String 后与 value 放入 Map
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }
}
