package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.chat.ChatTurnFlowLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AdmissionQueryCompileService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionQueryCompileService.class);

    private final AdmissionCompilerProperties properties;
    private final IntentServiceClient intentServiceClient;
    private final LocalAdmissionQueryCompiler localAdmissionQueryCompiler;
    private final AdmissionPriorSlotsBuilder priorSlotsBuilder;

    public AdmissionQueryCompileService(
            AdmissionCompilerProperties properties,
            IntentServiceClient intentServiceClient,
            LocalAdmissionQueryCompiler localAdmissionQueryCompiler,
            AdmissionPriorSlotsBuilder priorSlotsBuilder) {
        this.properties = properties;
        this.intentServiceClient = intentServiceClient;
        this.localAdmissionQueryCompiler = localAdmissionQueryCompiler;
        this.priorSlotsBuilder = priorSlotsBuilder;
    }

    public AdmissionQueryIr compile(String message) {
        return compile(message, List.of());
    }

    public AdmissionQueryIr compile(String message, List<String> priorUserMessagesNewestFirst) {
        List<String> priorMessages = priorUserMessagesNewestFirst == null
                ? List.of()
                : List.copyOf(priorUserMessagesNewestFirst);
        AdmissionSlotsIr priorSlots = priorSlotsBuilder.build(priorMessages);

        if (properties.enabled()) {
            Optional<AdmissionQueryIr> remote = intentServiceClient.compile(message, priorSlots, priorMessages);
            if (remote.isPresent()) {
                AdmissionQueryIr query = remote.get();
                logCompileResult("remote", message, priorMessages, priorSlots, query);
                return query;
            }
            log.info("admission-compiler remote miss, falling back to local compiler");
        }
        if (properties.fallbackToLocal()) {
            AdmissionQueryIr query = localAdmissionQueryCompiler.compile(message, priorMessages);
            logCompileResult("local", message, priorMessages, priorSlots, query);
            return query;
        }
        AdmissionQueryIr empty = AdmissionQueryIr.empty(message);
        logCompileResult("empty", message, priorMessages, priorSlots, empty);
        return empty;
    }

    private void logCompileResult(
            String source,
            String message,
            List<String> priorMessages,
            AdmissionSlotsIr priorSlots,
            AdmissionQueryIr query) {
        String detail = String.format(
                "source=%s task=%s score=%s provinces=%s subject=%s confidence=%.2f needs=%s unsupported=%s priorTurns=%d priorSlots=%s",
                source,
                query.task(),
                query.slots().score(),
                query.slots().provincesOrEmpty(),
                query.slots().subjectGroup(),
                query.confidence(),
                query.needsClarification(),
                query.unsupportedConstraints().stream()
                        .map(UnsupportedConstraintIr::constraintType)
                        .toList(),
                priorMessages.size(),
                priorSlots);
        log.info("AdmissionQueryCompile: {}", detail);
        if (ChatTurnFlowLog.active()) {
            ChatTurnFlowLog.step(ChatTurnFlowLog.Step.COMPILE_IR, "%s", detail);
        }
    }
}
