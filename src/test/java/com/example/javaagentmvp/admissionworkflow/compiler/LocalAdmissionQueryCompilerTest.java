package com.example.javaagentmvp.admissionworkflow.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntentClassifier;
import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LocalAdmissionQueryCompilerTest {

    private LocalAdmissionQueryCompiler compiler;

    @BeforeEach
    void setUp() throws Exception {
        AdmissionOntologyRegistry ontologyRegistry = new AdmissionOntologyRegistry();
        ontologyRegistry.load();
        RagProperties properties = new RagProperties(
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
                new RagProperties.Routing(List.of("招生简章|招生章程"), List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
        AdmissionIntentClassifier classifier = new AdmissionIntentClassifier(
                new RagQueryRouter(properties, new ConversationTurnResolver()),
                properties);
        AdmissionPriorSlotsBuilder priorSlotsBuilder = new AdmissionPriorSlotsBuilder(ontologyRegistry);
        compiler = new LocalAdmissionQueryCompiler(
                classifier,
                ontologyRegistry,
                new ConversationTurnResolver(),
                priorSlotsBuilder);
    }

    @Test
    void compilesYangtzeDeltaExclusionAndPreferences() {
        AdmissionQueryIr query = compiler.compile(
                "我要报考长三角的大学，不当老师，就业前景比较好，收入比较高，能进央国企");

        assertThat(query.toIntent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(query.slots().provincesOrEmpty()).containsExactlyInAnyOrder("江苏", "浙江", "上海");
        assertThat(query.filters().excludeSchoolNameContains()).contains("师范");
        assertThat(query.needsClarification()).contains("score", "subject_group");
        assertThat(query.preferences()).hasSize(3);
    }

    @Test
    void mergesScoreFromPriorTurn() {
        AdmissionQueryIr query = compiler.compile(
                "长三角不当老师",
                List.of("安徽考生630分物理类"));

        assertThat(query.slots().score()).isEqualTo(630);
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.slots().provincesOrEmpty()).contains("江苏", "浙江", "上海");
        assertThat(query.filters().excludeSchoolNameContains()).contains("师范");
    }

    @Test
    void followUpNortheastRankReplacesPriorProvinces() {
        AdmissionQueryIr query = compiler.compile(
                "在东北的排名",
                List.of("600分在长三角排名"));

        assertThat(query.task()).isEqualTo("search_rank");
        assertThat(query.slots().score()).isEqualTo(600);
        assertThat(query.slots().provincesOrEmpty()).containsExactlyInAnyOrder("辽宁", "吉林", "黑龙江");
        assertThat(query.slots().provincesOrEmpty()).doesNotContain("江苏", "浙江", "上海");
        assertThat(query.regions()).extracting(AdmissionRegionIr::phrase).contains("东北");
    }
}
