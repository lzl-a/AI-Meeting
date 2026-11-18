package com.hewei.hzyjy.xunzhi.agent.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hewei.hzyjy.xunzhi.agent.api.io.req.AgentConversationPageReqDTO;
import com.hewei.hzyjy.xunzhi.agent.api.io.req.AgentSessionCreateReqDTO;
import com.hewei.hzyjy.xunzhi.agent.api.io.resp.AgentConversationRespDTO;
import com.hewei.hzyjy.xunzhi.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.hewei.hzyjy.xunzhi.agent.api.io.resp.AgentSessionCreateRespDTO;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.service.AgentConversationService;
import com.hewei.hzyjy.xunzhi.agent.service.AgentMessageService;
import com.hewei.hzyjy.xunzhi.common.convention.annotation.CurrentUser;
import com.hewei.hzyjy.xunzhi.common.convention.context.UserContext;
import com.hewei.hzyjy.xunzhi.common.convention.result.Result;
import com.hewei.hzyjy.xunzhi.common.convention.result.Results;
import com.hewei.hzyjy.xunzhi.user.api.io.req.UserMessageReqDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.dev33.satoken.annotation.SaIgnore;

import java.util.List;

/**
 *Agent文字聊天接口
 * @author nageoffer
 * @date 2023/9/27
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/xunzhi/v1/agents")
@RequiredArgsConstructor
@SaIgnore


public class AgentController {

    private final BusinessAgentResolver businessAgentResolver;
    private final AgentMessageService agentMessageService;
    private final AgentConversationService agentConversationService;

    // ========== 【大作业演示临时模拟固定用户，注释@CurrentUser，关闭身份校验】 ==========
    private static final String MOCK_USERNAME = "hewei2323";
    private static final Long MOCK_USER_ID = 1L;

    /**
     * 创建Agent会话
     * @param requestParam 会话创建请求参数
     * @return 会话ID和标题
     */
    @PostMapping("/sessions")
    public Result<AgentSessionCreateRespDTO> createSession(
            @RequestBody AgentSessionCreateReqDTO requestParam
            /*, @CurrentUser UserContext currentUser*/) {
        // ========== 临时写死AgentId，跳过场景绑定，演示使用，上线恢复 ==========
        Long agentId = 1L;
        AgentSessionCreateRespDTO result = agentConversationService.createConversationWithTitle(
                MOCK_USERNAME,
                agentId,
                requestParam.getFirstMessage()
        );
        return Results.success(result);
    }


    /**
     * Agent文字聊天SSE接口
     * @return SSE流
     */
    @PostMapping("/sessions/{sessionId}/chat")
    public SseEmitter chat(
            @PathVariable String sessionId,
            @RequestBody UserMessageReqDTO requestParam
            /*, @CurrentUser UserContext currentUser*/) {
        requestParam.setUserName(MOCK_USERNAME);
        requestParam.setSessionId(sessionId);
        return agentMessageService.agentChatSse(requestParam, MOCK_USER_ID);
    }

    /**
     * 分页查询用户会话列表
     */
    @GetMapping("/conversations")
    public Result<IPage<AgentConversationRespDTO>> pageConversations(
            AgentConversationPageReqDTO requestParam
            /*, @CurrentUser UserContext currentUser*/) {
        return Results.success(agentConversationService.pageConversations(MOCK_USERNAME, requestParam));
    }

    /**
     * 查询会话历史消息
     */
    @GetMapping("/conversations/{sessionId}/messages")
    public Result<List<AgentMessageHistoryRespDTO>> getConversationHistory(
            @PathVariable String sessionId
            /*, @CurrentUser UserContext currentUser*/) {
        return Results.success(agentMessageService.getConversationHistory(sessionId, MOCK_USER_ID));
    }

    /**
     * 分页查询历史消息
     */
    @GetMapping("/messages/history")
    public Result<IPage<AgentMessageHistoryRespDTO>> pageHistoryMessages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size
            /*, @CurrentUser UserContext currentUser*/) {
        return Results.success(agentMessageService.pageHistoryMessages(sessionId, current, size, MOCK_USER_ID));
    }

    /**
     * 结束会话
     */
    @PutMapping("/conversations/{sessionId}/end")
    public Result<Void> endConversation(@PathVariable String sessionId
            /*, @CurrentUser UserContext currentUser*/) {
        agentConversationService.endConversation(sessionId, MOCK_USER_ID);
        return Results.success();
    }
}

