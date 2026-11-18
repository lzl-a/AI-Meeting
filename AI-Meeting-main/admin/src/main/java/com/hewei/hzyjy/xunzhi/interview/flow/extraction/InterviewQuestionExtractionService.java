package com.hewei.hzyjy.xunzhi.interview.flow.extraction;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentResolver;
import com.hewei.hzyjy.xunzhi.agent.application.BusinessAgentScene;
import com.hewei.hzyjy.xunzhi.agent.dao.entity.AgentPropertiesDO;
import com.hewei.hzyjy.xunzhi.interview.api.io.req.InterviewQuestionReqDTO;
import com.hewei.hzyjy.xunzhi.interview.api.io.resp.InterviewQuestionRespDTO;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardException;
import com.hewei.hzyjy.xunzhi.interview.application.guard.core.InterviewAiGuardStage;
import com.hewei.hzyjy.xunzhi.interview.application.guard.lock.InterviewAiSessionLockService;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewAiInvoker;
import com.hewei.hzyjy.xunzhi.interview.shared.InterviewResponseParser;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionCacheService;
import com.hewei.hzyjy.xunzhi.interview.service.InterviewQuestionService;
import com.hewei.hzyjy.xunzhi.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionExtractionService {

    private static final String EXTRACTION_PROMPT =
            "Extract technical interview questions from the uploaded resume. "
                    + "Always produce at least 5 questions in the questions array and never return an empty array. "
                    + "If the resume looks non-technical or lacks projects, still generate questions that probe the "
                    + "candidate's real experience, coursework, tools, problem solving and learning ability, "
                    + "instead of refusing or returning questions with an empty array. "
                    + "Return JSON only with keys questions, sugest, type, and resumeScore. "
                    + "Do not output smallTalk, greetings, or fallback chat content.";

    /**
     * 模型判定简历缺少可考察的技术内容时只会返回建议或闲聊，整条面试链路会卡在入口。
     * 这里准备一组通用题作为降级题库，保证流程可以继续；命中降级时会在建议里明确标注原因。
     */
    private static final List<String> FALLBACK_QUESTIONS = List.of(
            "请先做一个简短的自我介绍，重点讲一个你最近做过的项目。",
            "你在那个项目里具体负责哪一部分？遇到过最难的问题是什么，最后怎么解决的？",
            "Java 里 ArrayList 和 LinkedList 有什么区别？你在项目中会怎么选择？",
            "说说你对 Spring Boot 自动装配的理解，它主要解决了什么问题？",
            "如果线上接口响应突然变慢，你会从哪些方面排查？"
    );

    private final BusinessAgentResolver businessAgentResolver;
    private final XingChenAIClient xingChenAIClient;
    private final InterviewAiInvoker interviewAiInvoker;
    private final InterviewAiSessionLockService interviewAiSessionLockService;
    private final InterviewQuestionService interviewQuestionService;
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    private final InterviewResponseParser interviewResponseParser;

    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        InterviewQuestionRespDTO response = new InterviewQuestionRespDTO();
        response.setSessionId(reqDTO.getSessionId());
        response.setUserName(reqDTO.getUserName());

        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(
                BusinessAgentScene.INTERVIEW_QUESTION_EXTRACTION);
        reqDTO.setAgentId(agentProperties.getId());
        response.setIsSuccess(0);

        // 哈希计算是纯本地操作，在获取分布式锁之前完成，减少锁占用时间。
        String resumeContentHash = computeResumeHash(reqDTO.getResumePdf(), reqDTO.getSessionId());

        RLock heavyLock = null;
        long startTime = System.currentTimeMillis();
        try {
            // 同一 session 的提取属于重操作，先拿会话级重锁，避免并发上传/提取造成重复消耗和状态覆盖。
            heavyLock = interviewAiSessionLockService.acquire(reqDTO.getSessionId(), InterviewAiGuardStage.INTERVIEW_EXTRACTION);
            if (heavyLock == null) {
                response.setErrorMessage("AI_OVERLOADED: extraction is processing, please retry");
                return response;
            }

            String fileUrl = uploadResumeIfPresent(reqDTO, agentProperties, response);
            if (fileUrl == null) {
                return response;
            }

            String fullContent = interviewAiInvoker.callAiSyncWithFile(
                    EXTRACTION_PROMPT,
                    reqDTO.getSessionId(),
                    agentProperties,
                    fileUrl,
                    InterviewAiGuardStage.INTERVIEW_EXTRACTION,
                    interviewAiInvoker.buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EXTRACTION, reqDTO.getSessionId(), resumeContentHash)
            );

            long responseTime = System.currentTimeMillis() - startTime;
            reqDTO.setResumeFileUrl(fileUrl);

            // 先持久化原始响应，再做结构化解析；解析失败时仍可通过原始响应排障与回补。
            persistRawResponse(reqDTO, fullContent, responseTime);

            response.setResumeFileUrl(fileUrl);
            response.setResponseTime((int) responseTime);

            if (!populateStructuredResponse(reqDTO, response, fullContent)) {
                return response;
            }

            response.setIsSuccess(1);
            log.info("Interview question extraction completed, sessionId={}", reqDTO.getSessionId());
            return response;
        } catch (InterviewAiGuardException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Interview question extraction guarded failure, sessionId={}, code={}, message={}",
                    reqDTO.getSessionId(), e.getErrorCode(), e.getMessage());
            try {
                // 失败也落库，但仅记录错误信息；结构化字段覆盖保护在 service 层统一处理。
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction guard error record: {}", saveException.getMessage());
            }
            response.setErrorMessage(e.getMessage());
            response.setIsSuccess(0);
            return response;
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Interview question extraction failed: {}", e.getMessage(), e);
            try {
                interviewQuestionService.createFromAIResponse(
                        reqDTO,
                        "{\"error\":\"" + e.getMessage() + "\"}",
                        (int) responseTime,
                        null
                );
            } catch (Exception saveException) {
                log.error("Failed to save extraction error record: {}", saveException.getMessage());
            }

            response.setErrorMessage("interview question extraction failed: " + e.getMessage());
            response.setIsSuccess(0);
            return response;
        } finally {
            interviewAiSessionLockService.release(heavyLock);
        }
    }

    private String uploadResumeIfPresent(
            InterviewQuestionReqDTO reqDTO,
            AgentPropertiesDO agentProperties,
            InterviewQuestionRespDTO response) {
        if (reqDTO.getResumePdf() == null || reqDTO.getResumePdf().isEmpty()) {
            response.setErrorMessage("resume file does not exist");
            return null;
        }
        try {
            String fileUrl = xingChenAIClient.uploadFile(
                    reqDTO.getResumePdf(),
                    agentProperties.getApiKey(),
                    agentProperties.getApiSecret()
            );
            log.info("Resume uploaded successfully, url={}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("Resume upload failed: {}", e.getMessage());
            response.setErrorMessage("failed to upload resume file");
            return null;
        }
    }

    private void persistRawResponse(InterviewQuestionReqDTO reqDTO, String fullContent, long responseTime) {
        try {
            interviewQuestionService.createFromAIResponse(
                    reqDTO,
                    fullContent,
                    (int) responseTime,
                    null
            );
            log.info("Interview question response saved, sessionId={}", reqDTO.getSessionId());
        } catch (Exception e) {
            log.error("Failed to save interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(), e.getMessage());
        }
    }

    private boolean populateStructuredResponse(
            InterviewQuestionReqDTO reqDTO,
            InterviewQuestionRespDTO response,
            String fullContent) {
        try {
            log.info("Start parsing interview question response, sessionId={}, payloadLength={}, payloadHash={}",
                    reqDTO.getSessionId(),
                    fullContent == null ? 0 : fullContent.length(),
                    digestForLog(fullContent));

            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(fullContent);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                response.setErrorMessage(workflowErrorMessage);
                log.warn("Interview question workflow returned error, sessionId={}, message={}",
                        reqDTO.getSessionId(), workflowErrorMessage);
                return false;
            }

            String extractedContent = interviewResponseParser.extractContentFromInterviewResponse(fullContent);
            log.info("Extracted interview content summary, sessionId={}, contentLength={}, contentHash={}",
                    reqDTO.getSessionId(),
                    extractedContent == null ? 0 : extractedContent.length(),
                    digestForLog(extractedContent));
            if (StrUtil.isBlank(extractedContent)) {
                response.setErrorMessage("interview question response content is blank");
                return false;
            }

            Map<String, Object> responseMap = interviewResponseParser.extractStructuredResult(
                    extractedContent,
                    "questions",
                    "sugest",
                    "suggestions",
                    "resumeScore",
                    "type",
                    "smallTalk"
            );
            if (responseMap == null || responseMap.isEmpty()) {
                response.setErrorMessage("interview question response parse failed");
                log.warn("Interview question response parse failed, responseMap is null");
                return false;
            }

            log.info("Interview question response fields: {}", responseMap.keySet());
            Map<String, Object> resumeContext = buildResumeContext(responseMap);
            if (!resumeContext.isEmpty()) {
                interviewQuestionCacheService.cacheResumeContext(reqDTO.getSessionId(), resumeContext);
            }

            List<String> questions = normalizeStringList(responseMap.get("questions"));
            if (questions.isEmpty()) {
                String smallTalk = interviewResponseParser.asString(responseMap.get("smallTalk"));
                List<String> advice = normalizeSuggestions(responseMap);
                // 模型确认简历里没有可考察的技术内容时只返回建议（甚至闲聊），直接失败会让用户
                // 卡在入口且看不出原因。这里降级到内置题库继续流程，并把原因写进建议里提示用户。
                questions = new ArrayList<>(FALLBACK_QUESTIONS);
                List<String> merged = new ArrayList<>(advice);
                String reason = StrUtil.isNotBlank(smallTalk)
                        ? smallTalk
                        : (advice.isEmpty() ? "简历内容不足以生成技术面试题" : advice.get(0));
                merged.add("本次未从简历中提取到技术面试题（" + reason + "），题目由内置备用题库生成，"
                        + "建议补充技术项目经历后重新上传简历。");
                responseMap.put("sugest", merged);
                responseMap.put("suggestions", merged);
                log.warn("Interview question extraction returned no questions, fallback bank used, sessionId={}, smallTalk={}, adviceSize={}",
                        reqDTO.getSessionId(), smallTalk, advice.size());
            }

            interviewQuestionCacheService.cacheInterviewQuestions(reqDTO.getSessionId(), questions);
            Map<String, String> questionMap =
                    interviewQuestionCacheService.getSessionInterviewQuestions(reqDTO.getSessionId());
            response.setQuestions(questionMap);
            response.setQuestionCount(questions.size());
            interviewQuestionCacheService.initInterviewFlow(reqDTO.getSessionId(), questions.size());

            List<String> suggestions = normalizeSuggestions(responseMap);
            if (!suggestions.isEmpty()) {
                interviewQuestionCacheService.cacheInterviewSuggestions(reqDTO.getSessionId(), suggestions);
                Map<String, String> suggestionMap =
                        interviewQuestionCacheService.getSessionInterviewSuggestions(reqDTO.getSessionId());
                response.setSuggestions(suggestionMap);
                response.setSuggestionCount(suggestions.size());
            } else {
                log.warn("Interview question response does not contain suggestions");
            }

            // type 字段兼容历史别名，保证 interviewDirection/interviewType 在不同模型输出下都能回补。
            String interviewType = interviewResponseParser.asString(responseMap.get("type"));
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewType"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("direction"));
            }
            if (StrUtil.isBlank(interviewType)) {
                interviewType = interviewResponseParser.asString(responseMap.get("interviewDirection"));
            }
            if (StrUtil.isNotBlank(interviewType)) {
                interviewQuestionCacheService.cacheInterviewDirection(reqDTO.getSessionId(), interviewType);
                response.setInterviewType(interviewType);
            } else {
                log.warn("Interview question response does not contain type field");
            }

            Integer resumeScore = interviewResponseParser.parseScoreFromResponse(responseMap, "resumeScore");
            if (resumeScore != null) {
                interviewQuestionCacheService.cacheResumeScore(reqDTO.getSessionId(), resumeScore);
                response.setResumeScore(resumeScore);
            } else {
                log.warn("Interview question response does not contain valid resumeScore field");
            }

            // 结构化二次落库用于 Redis 丢失后的恢复来源，避免报告阶段出现字段缺失。
            persistStructuredFields(reqDTO, questions, suggestions, resumeScore, interviewType, resumeContext);
            interviewQuestionCacheService.resetSessionScore(reqDTO.getSessionId());
            log.info("Session score reset, sessionId={}", reqDTO.getSessionId());
            return true;
        } catch (Exception cacheException) {
            response.setErrorMessage("failed to parse interview question response");
            log.error(
                    "Failed to cache interview question response, sessionId={}, error={}",
                    reqDTO.getSessionId(),
                    cacheException.getMessage()
            );
            return false;
        }
    }

    private List<String> normalizeSuggestions(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> suggestions = normalizeStringList(responseMap.get("sugest"));
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        return normalizeStringList(responseMap.get("suggestions"));
    }

    private List<String> normalizeStringList(Object value) {
        return interviewResponseParser.asStringList(value);
    }

    /**
     * 计算简历文件内容的 SHA-256 哈希，用于 single-flight 去重。
     * 相同文件内容产生相同哈希，避免因上传 URL 每次变化导致去重失效。
     *
     * @param resumePdf 简历文件
     * @param sessionId 会话标识，仅用于日志
     * @return 文件内容的 SHA-256 十六进制字符串，文件为空时返回 null
     */
    private String computeResumeHash(MultipartFile resumePdf, String sessionId) {
        if (resumePdf == null || resumePdf.isEmpty()) {
            log.debug("Resume PDF is null or empty, cannot compute content hash, sessionId={}", sessionId);
            return null;
        }
        try {
            byte[] fileBytes = resumePdf.getBytes();
            String hash = DigestUtil.sha256Hex(fileBytes);
            log.debug("Computed resume content hash, sessionId={}, hash={}", sessionId, hash);
            return hash;
        } catch (Exception e) {
            log.warn("Failed to read resume PDF bytes for content hashing, sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    private String digestForLog(String value) {
        if (StrUtil.isBlank(value)) {
            return "-";
        }
        return DigestUtil.sha256Hex(value).substring(0, 16);
    }

    private void persistStructuredFields(
            InterviewQuestionReqDTO reqDTO,
            List<String> questions,
            List<String> suggestions,
            Integer resumeScore,
            String interviewType,
            Map<String, Object> resumeContext) {
        try {
            interviewQuestionService.upsertStructuredExtraction(
                    reqDTO.getSessionId(),
                    reqDTO.getUserName(),
                    reqDTO.getAgentId(),
                    reqDTO.getResumeFileUrl(),
                    questions,
                    suggestions,
                    resumeScore,
                    interviewType,
                    resumeContext
            );
        } catch (Exception ex) {
            log.warn("Failed to persist structured extraction fields, sessionId={}, error={}",
                    reqDTO.getSessionId(), ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildResumeContext(Map<String, Object> responseMap) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (responseMap == null || responseMap.isEmpty()) {
            return context;
        }
        for (Map.Entry<String, Object> entry : responseMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if ("questions".equals(key) || "sugest".equals(key) || "suggestions".equals(key)) {
                continue;
            }
            context.put(key, value);
        }
        return context;
    }
}
