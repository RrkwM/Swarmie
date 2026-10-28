package dev.swarmie.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-file contract for the spawn egg.
 *
 * <p>These tests do not boot Minecraft: they verify that the shipped client
 * item definition, model, recipe, advancement and lang entries stay mutually
 * consistent. Real in-game spawning is a separate, real-client check.
 */
class SwarmieSpawnEggDataTest {
    private static final String ITEM_ID = "swarmie:swarmie_spawn_egg";
    private static final String MODEL_ID = "swarmie:item/swarmie_spawn_egg";

    @Test
    void itemDefinitionAndModelPointAtTheSameTexture() throws IOException {
        JsonObject definition = resource("/assets/swarmie/items/swarmie_spawn_egg.json");
        assertEquals(MODEL_ID, definition.getAsJsonObject("model").get("model").getAsString());

        JsonObject model = resource("/assets/swarmie/models/item/swarmie_spawn_egg.json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        assertEquals(MODEL_ID, model.getAsJsonObject("textures").get("layer0").getAsString());
    }

    @Test
    void shapedRecipeCraftsTheSpawnEggAndHasAnUnlockAdvancement() throws IOException {
        JsonObject recipe = resource("/data/swarmie/recipe/swarmie_spawn_egg.json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals(ITEM_ID, recipe.getAsJsonObject("result").get("id").getAsString());

        JsonObject key = recipe.getAsJsonObject("key");
        assertEquals("minecraft:iron_ingot", key.get("I").getAsString());
        assertEquals("minecraft:redstone", key.get("R").getAsString());
        assertEquals("minecraft:copper_ingot", key.get("C").getAsString());
        assertEquals(3, recipe.getAsJsonArray("pattern").size());

        JsonObject advancement = resource(
                "/data/swarmie/advancement/recipes/misc/swarmie_spawn_egg.json");
        assertEquals("minecraft:recipes/root", advancement.get("parent").getAsString());
        assertTrue(advancement.getAsJsonObject("rewards").getAsJsonArray("recipes")
                .toString().contains(ITEM_ID));
    }

    @Test
    void bothLanguagesNameTheItem() throws IOException {
        assertTrue(resource("/assets/swarmie/lang/en_us.json")
                .has("item.swarmie.swarmie_spawn_egg"));
        assertTrue(resource("/assets/swarmie/lang/zh_cn.json")
                .has("item.swarmie.swarmie_spawn_egg"));
    }

    private static JsonObject resource(String path) throws IOException {
        try (InputStream stream = SwarmieSpawnEggDataTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing resource " + path);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }
}
