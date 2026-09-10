package com.anuragbhandary.jobradar.apply.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anuragbhandary.jobradar.knowledge.ApplicationContext;
import com.anuragbhandary.jobradar.knowledge.Contexts;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which system decides what goes into a real form, and how to change your mind.
 *
 * <p>One flag, both implementations compiled in, and neither deleted. The legacy
 * mapper stays as three things at once: the oracle the shadow compares against,
 * the fallback when the flag is off, and the reference when the two disagree
 * about a live application at eleven at night.
 *
 * <p>Rolling back is a config edit and a restart. That is deliberate: the
 * alternative on the path that fills an employer's form is reverting a commit,
 * and a rollback that needs a build is not a rollback.
 */
class ResolverAuthorityTest {

    private static final FormField FIELD = new FormField("#f", "First name",
            FormField.ControlType.TEXT, List.of(), true, FieldKind.FIRST_NAME);

    private static FormFiller filler(boolean authoritative, FieldMapper mapper,
            KnowledgeAnswers knowledge) {
        return new FormFiller(mapper, knowledge, authoritative);
    }

    @Test
    @DisplayName("with the flag off the legacy mapper answers, and knowledge is untouched")
    void offUsesTheLegacyMapper() {
        FieldMapper mapper = mock(FieldMapper.class);
        KnowledgeAnswers knowledge = mock(KnowledgeAnswers.class);
        when(mapper.answer(any(), any(), any())).thenReturn(Answer.profile("Anurag"));

        Answer answer = filler(false, mapper, knowledge)
                .answerFor(FIELD, null, null, Contexts.germanyOnsite());

        assertThat(answer.value()).isEqualTo("Anurag");
        verify(knowledge, never()).answer(any(), any(), any());
    }

    @Test
    @DisplayName("with the flag on the knowledge system answers, and the mapper is untouched")
    void onUsesTheResolver() {
        FieldMapper mapper = mock(FieldMapper.class);
        KnowledgeAnswers knowledge = mock(KnowledgeAnswers.class);
        when(knowledge.answer(any(), any(), any())).thenReturn(Answer.profile("Anurag"));

        Answer answer = filler(true, mapper, knowledge)
                .answerFor(FIELD, null, null, Contexts.germanyOnsite());

        assertThat(answer.value()).isEqualTo("Anurag");
        verify(mapper, never()).answer(any(), any(), any());
    }

    @Test
    @DisplayName("with no context the legacy mapper answers even when the flag is on")
    void noContextFallsBack() {
        // Nothing in the knowledge system can resolve without an application
        // context, so a caller that has none gets the path that does not need
        // one rather than a form full of blanks.
        FieldMapper mapper = mock(FieldMapper.class);
        KnowledgeAnswers knowledge = mock(KnowledgeAnswers.class);
        when(mapper.answer(any(), any(), any())).thenReturn(Answer.profile("Anurag"));

        Answer answer = filler(true, mapper, knowledge)
                .answerFor(FIELD, null, null, (ApplicationContext) null);

        assertThat(answer.value()).isEqualTo("Anurag");
        verify(knowledge, never()).answer(any(), any(), any());
    }

    @Test
    @DisplayName("neither implementation is removed by the other being in use")
    void bothStayCompiledIn() {
        // The mapper is the shadow's oracle and the fallback. A test that fails
        // when someone deletes it is cheaper than finding out during an
        // application.
        FieldMapper mapper = mock(FieldMapper.class);
        KnowledgeAnswers knowledge = mock(KnowledgeAnswers.class);
        when(mapper.answer(any(), any(), any())).thenReturn(Answer.profile("legacy"));
        when(knowledge.answer(any(), any(), any())).thenReturn(Answer.profile("knowledge"));

        assertThat(filler(true, mapper, knowledge)
                .answerFor(FIELD, null, null, Contexts.germanyOnsite()).value())
                .isEqualTo("knowledge");
        assertThat(filler(false, mapper, knowledge)
                .answerFor(FIELD, null, null, Contexts.germanyOnsite()).value())
                .isEqualTo("legacy");
    }

    @Test
    @DisplayName("a settled answer still wins over both, so resuming keeps his decisions")
    void settledAnswersOutrankBoth() {
        FieldMapper mapper = mock(FieldMapper.class);
        KnowledgeAnswers knowledge = mock(KnowledgeAnswers.class);
        when(knowledge.answer(any(), any(), any())).thenReturn(Answer.profile("knowledge"));

        var settled = com.anuragbhandary.jobradar.apply.PreparedAnswers.from(List.of(
                settledField("First name", "Anurag as he typed it")));

        assertThat(settled.forField(FIELD)).isPresent();
        assertThat(settled.forField(FIELD).orElseThrow().value())
                .isEqualTo("Anurag as he typed it");
    }

    private static com.anuragbhandary.jobradar.apply.ApplicationField settledField(
            String label, String value) {
        var field = new com.anuragbhandary.jobradar.apply.ApplicationField(1L, label,
                com.anuragbhandary.jobradar.apply.FieldState.RESOLVED);
        field.setResolvedValue(value);
        return field;
    }

}
