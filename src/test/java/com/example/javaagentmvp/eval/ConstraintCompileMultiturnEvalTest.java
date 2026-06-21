package com.example.javaagentmvp.eval;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionCompilerProperties;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionOntologyRegistry;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPriorSlotsBuilder;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.IntentServiceClient;
import com.example.javaagentmvp.admissionworkflow.compiler.LocalAdmissionQueryCompiler;
import com.example.javaagentmvp.admissionworkflow.compiler.UnsupportedConstraintRecorder;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Multi-turn IR compile golden set — mirrors Python {@code run_eval.py} against
 * {@code eval/cases/constraint_compile_multiturn.jsonl} (skips {@code status=target}).
 */
class ConstraintCompileMultiturnEvalTest {

    private AdmissionQueryCompileService compileService;

    @BeforeEach
    void setUp() throws Exception {
        compileService = compileService();
    }

    @Test
    void runsMultiturnConstraintCases() throws Exception {
        Path casesPath = EvalCaseLoader.projectRoot().resolve("eval/cases/constraint_compile_multiturn.jsonl");
        List<EvalCaseLoader.ConstraintCompileCase> cases = EvalCaseLoader.loadConstraintCompileCases(casesPath);

        assertThat(cases).isNotEmpty();
        List<String> failures = new ArrayList<>();
        for (EvalCaseLoader.ConstraintCompileCase evalCase : cases) {
            AdmissionQueryIr query = compileService.compile(
                    evalCase.input(),
                    evalCase.priorUserMessagesNewestFirst());
            List<String> errors = assertExpect(evalCase.id(), evalCase.expect(), query);
            if (!errors.isEmpty()) {
                failures.add(evalCase.id() + ": " + String.join("; ", errors));
            }
        }
        assertThat(failures)
                .as("constraint_compile_multiturn failures")
                .isEmpty();
    }

    private static List<String> assertExpect(
            String caseId,
            EvalCaseLoader.ConstraintCompileExpect expect,
            AdmissionQueryIr query) {
        List<String> errors = new ArrayList<>();
        if (expect.task() != null && !expect.task().equals(query.task())) {
            errors.add("task expected " + expect.task() + " got " + query.task());
        }
        if (expect.score() != null && !expect.score().equals(query.slots().score())) {
            errors.add("score expected " + expect.score() + " got " + query.slots().score());
        }
        if (expect.subjectGroup() != null && !expect.subjectGroup().equals(query.slots().subjectGroup())) {
            errors.add("subject_group expected " + expect.subjectGroup() + " got " + query.slots().subjectGroup());
        }
        if (!expect.provinces().isEmpty()) {
            List<String> actual = query.slots().provincesOrEmpty();
            if (expect.provincesExact()) {
                if (!actual.equals(expect.provinces())) {
                    errors.add("provinces expected exact " + expect.provinces() + " got " + actual);
                }
            }
            else {
                for (String province : expect.provinces()) {
                    if (!actual.contains(province)) {
                        errors.add("provinces missing " + province + " in " + actual);
                    }
                }
            }
        }
        for (String token : expect.excludeSchool()) {
            if (!query.filters().excludeSchoolNameContains().contains(token)) {
                errors.add("exclude_school missing " + token + " in "
                        + query.filters().excludeSchoolNameContains());
            }
        }
        for (String token : expect.excludeMajor()) {
            boolean matched = query.filters().excludeMajorKeywords().stream().anyMatch(m -> m.contains(token));
            if (!matched) {
                errors.add("exclude_major missing " + token + " in " + query.filters().excludeMajorKeywords());
            }
        }
        for (String token : expect.includeMajor()) {
            boolean matched = query.filters().includeMajorKeywords().stream().anyMatch(m -> m.contains(token));
            if (!matched) {
                errors.add("include_major missing " + token + " in " + query.filters().includeMajorKeywords());
            }
        }
        for (String token : expect.includeMajorDisciplineGroups()) {
            if (!query.filters().includeMajorDisciplineGroups().contains(token)) {
                errors.add("include_major_discipline_groups missing " + token + " in "
                        + query.filters().includeMajorDisciplineGroups());
            }
        }
        for (String token : expect.includeDisciplineCategories()) {
            if (!query.filters().includeDisciplineCategories().contains(token)) {
                errors.add("include_discipline_categories missing " + token + " in "
                        + query.filters().includeDisciplineCategories());
            }
        }
        if (!expect.preferences().isEmpty()) {
            var dims = query.preferences().stream()
                    .map(p -> p.dimension())
                    .collect(java.util.stream.Collectors.toSet());
            for (String dim : expect.preferences()) {
                if (!dims.contains(dim)) {
                    errors.add("preferences missing " + dim + " in " + dims);
                }
            }
        }
        if (!expect.needsClarification().isEmpty()) {
            for (String field : expect.needsClarification()) {
                if (!query.needsClarification().contains(field)) {
                    errors.add("needs_clarification missing " + field + " in " + query.needsClarification());
                }
            }
            for (String field : query.needsClarification()) {
                if (!expect.needsClarification().contains(field)) {
                    errors.add("needs_clarification unexpected " + field);
                }
            }
        }
        if (!expect.unsupportedConstraints().isEmpty()) {
            var types = query.unsupportedConstraints().stream()
                    .map(c -> c.constraintType())
                    .collect(java.util.stream.Collectors.toSet());
            for (String constraintType : expect.unsupportedConstraints()) {
                if (!types.contains(constraintType)) {
                    errors.add("unsupported_constraints missing " + constraintType + " in " + types);
                }
            }
        }
        if (expect.blocksMcp() != null && expect.blocksMcp() != query.blocksMcpExecution()) {
            errors.add("blocks_mcp expected " + expect.blocksMcp() + " got " + query.blocksMcpExecution());
        }
        return errors;
    }

    private static AdmissionQueryCompileService compileService() throws Exception {
        AdmissionOntologyRegistry ontologyRegistry = new AdmissionOntologyRegistry();
        ontologyRegistry.load();
        AdmissionPriorSlotsBuilder priorSlotsBuilder = new AdmissionPriorSlotsBuilder(ontologyRegistry);
        LocalAdmissionQueryCompiler localCompiler = new LocalAdmissionQueryCompiler(
                ontologyRegistry,
                priorSlotsBuilder,
                new RagQueryRouter(evalRagProperties()));
        return new AdmissionQueryCompileService(
                AdmissionCompilerProperties.defaults(),
                new IntentServiceClient(AdmissionCompilerProperties.defaults(), RestClient.builder()),
                localCompiler,
                priorSlotsBuilder);
    }

    private static RagProperties evalRagProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                4,
                0.7,
                true,
                "",
                new RagProperties.Routing(
                        List.of("招生简章|招生章程", "\\brag\\b", "微调|知识库"),
                        List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }
}
