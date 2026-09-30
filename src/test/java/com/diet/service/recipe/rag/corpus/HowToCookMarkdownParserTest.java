package com.diet.service.recipe.rag.corpus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HowToCookMarkdownParserTest {

    private final HowToCookMarkdownParser parser = new HowToCookMarkdownParser();

    @Test
    void parsesRealHowToCookHeadingAndListStructure() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/recipe/howtocook/dishes/vegetable_dish/fixture.md")) {
            assertThat(stream).isNotNull();
            String markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            HowToCookMarkdownParser.ParsedRecipe recipe = parser.parse(markdown).orElseThrow();

            assertThat(recipe.title()).isEqualTo("西红柿炒鸡蛋");
            assertThat(recipe.ingredients()).containsExactly("西红柿", "鸡蛋", "食用油", "盐");
            assertThat(recipe.steps()).hasSize(3);
            assertThat(recipe.steps().get(1)).contains("鸡蛋打散", "不要炒得过老");
            assertThat(recipe.sections()).containsKeys("必备原料和工具", "计算", "操作", "附加内容");
        }
    }

    @Test
    void rejectsIndexPagesWithoutRecipeSections() {
        assertThat(parser.parse("# 菜谱目录\n\n- [西红柿炒鸡蛋](fixture.md)\n")).isEmpty();
    }
}
