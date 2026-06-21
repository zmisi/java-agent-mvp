package com.example.javaagentmvp.admissionworkflow.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
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
        AdmissionPriorSlotsBuilder priorSlotsBuilder = new AdmissionPriorSlotsBuilder(ontologyRegistry);
        compiler = new LocalAdmissionQueryCompiler(
                ontologyRegistry,
                priorSlotsBuilder,
                new RagQueryRouter(minimalRagProperties()));
    }

    private static com.example.javaagentmvp.rag.RagProperties minimalRagProperties() {
        return new com.example.javaagentmvp.rag.RagProperties(
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
                new com.example.javaagentmvp.rag.RagProperties.Routing(
                        List.of("招生简章|招生章程", "\\brag\\b", "微调|知识库"),
                        List.of("\\d{3,4}\\s*分")),
                new com.example.javaagentmvp.rag.RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new com.example.javaagentmvp.rag.RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    @Test
    void inheritsScoreForRankFollowUpAfterMajorSearch() {
        AdmissionQueryIr query = compiler.compile(
                "那我排名多少",
                List.of("安徽物理类620分能上什么专业"));

        assertThat(query.task()).isEqualTo("search_rank");
        assertThat(query.slots().score()).isEqualTo(620);
        assertThat(query.slots().provincesOrEmpty()).contains("安徽");
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.needsClarification()).isEmpty();
    }

    @Test
    void compilesRankBasedMajorSearchWithoutScoreClarification() {
        AdmissionQueryIr query = compiler.compile("排名10000名 安徽 物理类 能报什么专业");

        assertThat(query.toIntent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(query.task()).isEqualTo("search_majors");
        assertThat(query.slots().rank()).isEqualTo(10000);
        assertThat(query.slots().score()).isNull();
        assertThat(query.slots().provincesOrEmpty()).containsExactly("安徽");
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.needsClarification()).isEmpty();
    }

    @Test
    void compilesYangtzeDeltaExclusionAndUnsupportedEmploymentSignals() {
        AdmissionQueryIr query = compiler.compile(
                "我要报考长三角的大学，不当老师，就业前景比较好，收入比较高，能进央国企");

        assertThat(query.toIntent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(query.slots().provincesOrEmpty()).containsExactlyInAnyOrder("江苏", "浙江", "上海");
        assertThat(query.filters().excludeSchoolNameContains()).contains("师范");
        assertThat(query.needsClarification()).contains("score", "subject_group");
        assertThat(query.preferences()).isEmpty();
        assertThat(query.unsupportedConstraints())
                .extracting(UnsupportedConstraintIr::constraintType)
                .containsExactlyInAnyOrder("employment_data", "salary_data", "state_owned_employability");
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
    void followUpNortheastAfterProvinceChainInheritsRankWithoutClarification() {
        AdmissionQueryIr query = compiler.compile(
                "在东北",
                List.of("在安徽", "600分在江苏的排名"));

        assertThat(query.task()).isEqualTo("search_rank");
        assertThat(query.slots().score()).isEqualTo(600);
        assertThat(query.slots().provincesOrEmpty()).containsExactlyInAnyOrder("辽宁", "吉林", "黑龙江");
        assertThat(query.slots().provincesOrEmpty()).doesNotContain("江苏", "安徽");
        assertThat(query.needsClarification()).isEmpty();
        assertThat(query.blocksMcpExecution()).isFalse();
    }

    @Test
    void subjectGroupClarificationAfterNortheastKeepsOnlyNortheastProvinces() {
        AdmissionQueryIr query = compiler.compile(
                "物理类",
                List.of("在东北", "在安徽", "600分在江苏的排名"));

        assertThat(query.task()).isEqualTo("search_rank");
        assertThat(query.slots().score()).isEqualTo(600);
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.slots().provincesOrEmpty()).containsExactlyInAnyOrder("辽宁", "吉林", "黑龙江");
        assertThat(query.blocksMcpExecution()).isFalse();
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

    @Test
    void unsupportedConstraintsAreDetectedOnlyForCurrentTurnMessage() {
        AdmissionQueryIr query = compiler.compile(
                "600分安徽物理类",
                List.of("只看就业率高的专业"));

        assertThat(query.unsupportedConstraints()).isEmpty();
    }

    @Test
    void detectsUnsupportedEmploymentRatePhrase() {
        AdmissionQueryIr query = compiler.compile("只看就业率高的专业");

        assertThat(query.unsupportedConstraints()).isNotEmpty();
        assertThat(query.unsupportedConstraints())
                .extracting(UnsupportedConstraintIr::constraintType)
                .contains("employment_data");
    }

    @Test
    void detectsUnsupportedPostgradEmploymentSalaryAndQsRanking() {
        AdmissionQueryIr query = compiler.compile("620分江苏物理类，保研率高的，就业好的，薪资如何，QS排名");

        assertThat(query.unsupportedConstraints())
                .extracting(UnsupportedConstraintIr::constraintType)
                .contains("postgraduate_recommendation_rate", "employment_data", "salary_data", "university_ranking");
    }

    @Test
    void detectsSupportedEngineeringMajorCategory() {
        AdmissionQueryIr query = compiler.compile(
                "只看工科的专业",
                List.of("物理类", "在安徽可以报考什么学校专业", "600分在安徽排名"));

        assertThat(query.task()).isEqualTo("search_majors");
        assertThat(query.slots().score()).isEqualTo(600);
        assertThat(query.slots().provincesOrEmpty()).containsExactly("安徽");
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.filters().includeMajorKeywords()).isEmpty();
        assertThat(query.filters().includeMajorDisciplineGroups()).containsExactly("工科");
        assertThat(query.unsupportedConstraints()).isEmpty();
        assertThat(query.blocksMcpExecution()).isFalse();
    }

    @Test
    void greetingAfterMajorSearchDoesNotInheritPriorContext() {
        AdmissionQueryIr query = compiler.compile(
                "你好",
                List.of("物理类", "在安徽可以报考什么学校专业", "600分在安徽排名"));

        assertThat(query.task()).isEqualTo("unknown");
        assertThat(query.slots().score()).isNull();
        assertThat(FixedGuidanceSupport.requiresFixedGuidance(query)).isTrue();
    }

    @Test
    void offTopicUtteranceAfterMajorSearchRequiresFixedGuidance() {
        AdmissionQueryIr query = compiler.compile(
                "哈哈",
                List.of("物理类", "在安徽可以报考什么学校专业", "600分在安徽排名"));

        assertThat(query.task()).isEqualTo("unknown");
        assertThat(query.slots().score()).isNull();
        assertThat(FixedGuidanceSupport.requiresFixedGuidance(query)).isTrue();
    }

    @Test
    void partialScoreOnlyPromotesToClarificationNotFixedGuidance() {
        AdmissionQueryIr query = compiler.compile("600分");

        assertThat(query.task()).isEqualTo("search_majors");
        assertThat(query.needsClarification()).contains("provinces", "subject_group");
        assertThat(FixedGuidanceSupport.requiresFixedGuidance(query)).isFalse();
    }

    @Test
    void detectsSupportedEconomicsDisciplineCategory() {
        AdmissionQueryIr query = compiler.compile("只看经济学");

        assertThat(query.filters().includeDisciplineCategories()).containsExactly("经济学");
        assertThat(query.filters().includeMajorDisciplineGroups()).isEmpty();
        assertThat(query.unsupportedConstraints()).isEmpty();
        assertThat(query.needsClarification()).doesNotContain("major_category");
    }

    @Test
    void asksMajorCategoryClarificationForUnknownCategory() {
        AdmissionQueryIr query = compiler.compile(
                "只看法学专业",
                List.of("物理类", "在安徽可以报考什么学校专业", "600分在安徽排名"));

        assertThat(query.needsClarification()).contains("major_category");
        assertThat(query.blocksMcpExecution()).isTrue();
        assertThat(query.filters().hasMajorCategoryFilter()).isFalse();
    }

    @Test
    void majorCategoryClarificationMessageListsSupportedOptions() {
        String message = ClarificationSupport.buildMessage(List.of("major_category"));

        assertThat(message).contains("工科/理科/文科/医学/经管");
    }

    @Test
    void detectsUnsupportedTuitionAndPublicSchoolConstraints() {
        AdmissionQueryIr query = compiler.compile("620分江苏公办，学费便宜，物理类");

        assertThat(query.unsupportedConstraints()).hasSize(2);
        assertThat(query.unsupportedConstraints())
                .extracting(UnsupportedConstraintIr::constraintType)
                .containsExactlyInAnyOrder("school_nature_public", "tuition");
        assertThat(query.slots().score()).isEqualTo(620);
        assertThat(query.slots().provincesOrEmpty()).contains("江苏");
    }
}
