package com.anuragbhandary.jobradar.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HtmlTest {

    @Test
    @DisplayName("strips tags and collapses whitespace")
    void stripsTags() {
        assertThat(Html.toPlainText("<p>Hello   <b>world</b></p>")).isEqualTo("Hello world");
    }

    @Test
    @DisplayName("unescapes the layer Greenhouse adds, then the entities underneath it")
    void unescapesTwice() {
        // Greenhouse escapes the whole content field once, so an entity that was
        // already in the source HTML arrives double-escaped.
        assertThat(Html.toPlainText("&lt;p&gt;Java&amp;nbsp;17&lt;/p&gt;")).isEqualTo("Java 17");
    }

    @Test
    @DisplayName("keeps list items as separate words")
    void separatesBlockElements() {
        // Without a boundary space this reads "JavaPython", and any keyword
        // matching over the description would miss both.
        assertThat(Html.toPlainText("<ul><li>Java</li><li>Python</li></ul>"))
                .isEqualTo("Java Python");
    }

    @Test
    @DisplayName("resolves numeric entities in both decimal and hex")
    void resolvesNumericEntities() {
        assertThat(Html.toPlainText("we&#39;re")).isEqualTo("we're");
        assertThat(Html.toPlainText("we&#x27;re")).isEqualTo("we're");
    }

    @Test
    @DisplayName("leaves an unknown entity alone rather than dropping it")
    void preservesUnknownEntities() {
        // Silently deleting text from a description is worse than leaving an
        // ampersand in it.
        assertThat(Html.toPlainText("A &weirdthing; B")).isEqualTo("A &weirdthing; B");
    }

    @Test
    @DisplayName("null and blank input give an empty string")
    void handlesAbsentInput() {
        assertThat(Html.toPlainText(null)).isEmpty();
        assertThat(Html.toPlainText("   ")).isEmpty();
    }
}
