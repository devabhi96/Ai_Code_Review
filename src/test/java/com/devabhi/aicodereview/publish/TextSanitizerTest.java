package com.devabhi.aicodereview.publish;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    private static final String Z = "\u200B";

    private static String prose(String text) {
        return TextSanitizer.prose(text, 2000);
    }

    @Test
    void neutralizesMentions() {
        assertThat(prose("cc @octocat please")).isEqualTo("cc @" + Z + "octocat please");
        assertThat(prose("(@bob)")).isEqualTo("(@" + Z + "bob)");
    }

    @Test
    void neutralizesTeamMentions() {
        assertThat(prose("ping @acme/core-team")).isEqualTo("ping @" + Z + "acme/core-team");
    }

    @Test
    void leavesEmailAddressesAlone() {
        assertThat(prose("mail a@b.com")).isEqualTo("mail a@b.com");
    }

    @Test
    void escapesHtml() {
        assertThat(prose("<script>alert(1)</script>")).isEqualTo("&lt;script>alert(1)&lt;/script>");
    }

    @Test
    void hiddenMarkersCannotBeInjected() {
        assertThat(prose("x <!-- aicodereview:run=1 --> y")).doesNotContain("<");
    }

    @Test
    void removesImages() {
        String result = prose("![x](https://evil.example/a.png) text");
        assertThat(result).isEqualTo("[image removed] text");
        assertThat(result).doesNotContain("evil.example");
    }

    @Test
    void keepsLinkTextButDropsTheTarget() {
        assertThat(prose("see [docs](https://evil.example/x) now")).isEqualTo("see docs now");
    }

    @Test
    void removesBareUrls() {
        String result = prose("visit https://evil.example/path?q=1 or www.evil.example today");
        assertThat(result).isEqualTo("visit [link removed] or [link removed] today");
    }

    @Test
    void neutralizesIssueReferencesButNotHtmlEntities() {
        assertThat(prose("fixes #123 and &#39;")).isEqualTo("fixes #" + Z + "123 and &#39;");
    }

    @Test
    void stripsControlCharactersButKeepsNewlinesAndTabs() {
        assertThat(prose("a\u0000b\u0007c\nd\te")).isEqualTo("abc\nd\te");
    }

    @Test
    void truncatesWithAnEllipsis() {
        String result = TextSanitizer.prose("x".repeat(50), 10);
        assertThat(result).hasSize(10).endsWith("\u2026");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(prose(null)).isEmpty();
    }

    @Test
    void sanitizingTwiceChangesNothingMore() {
        String messy = "hi @bob [a](http://x.example) ![i](http://y.example/p.png) <b> https://z.example #12";
        assertThat(prose(prose(messy))).isEqualTo(prose(messy));
    }

    @Test
    void fenceIsLongerThanAnyBacktickRunInTheCode() {
        assertThat(TextSanitizer.fenceFor("plain code")).isEqualTo("```");
        assertThat(TextSanitizer.fenceFor("a ``` b")).isEqualTo("````");
        assertThat(TextSanitizer.fenceFor("``````")).isEqualTo("`".repeat(7));
        assertThat(TextSanitizer.fenceFor(null)).isEqualTo("```");
    }
}