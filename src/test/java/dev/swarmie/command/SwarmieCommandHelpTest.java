package dev.swarmie.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwarmieCommandHelpTest {
    @Test
    void rootHelpListsEveryCommandFamilyAndTheDebugBoundary() {
        String text = String.join("\n", SwarmieCommandHelp.lines(""));

        for (String command : List.of(
                "/swarmie help", "/swarmie list", "/swarmie select", "/swarmie selected",
                "/swarmie spawn", "/swarmie remove", "/swarmie fly", "/swarmie stop",
                "/swarmie status", "/swarmie results", "/swarmie cancel",
                "/swarmie mine", "/swarmie use", "/swarmie inventory",
                "/swarmie query", "/swarmie sensor", "/swarmie adapter")) {
            assertTrue(text.contains(command), "missing " + command);
        }
        assertTrue(text.contains("not the public JSONL/Python API"));
        assertTrue(text.contains("bypasses Runtime"));
        assertTrue(text.contains("DCPU/MMIO is test-only"));
    }

    @Test
    void topicHelpCoversSelectionFlightActionsInventoryQueryAdapterAndLifecycle() {
        assertEquals(List.of("selection", "flight", "actions", "inventory", "query", "adapter", "lifecycle"),
                SwarmieCommandHelp.TOPICS);
        for (String topic : SwarmieCommandHelp.TOPICS) {
            List<String> lines = SwarmieCommandHelp.lines(topic);
            assertFalse(lines.isEmpty(), "empty help for " + topic);
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("/swarmie")),
                    "no command example for " + topic);
        }
        assertTrue(String.join("\n", SwarmieCommandHelp.lines("inventory"))
                .contains("not a normal gameplay API"));
        assertTrue(String.join("\n", SwarmieCommandHelp.lines("flight"))
                .contains("bypasses the Runtime"));
        assertTrue(String.join("\n", SwarmieCommandHelp.lines("selection"))
                .contains("Selects by list index"));
    }

    @Test
    void unknownTopicIsExplicit() {
        String text = String.join("\n", SwarmieCommandHelp.lines("unknown"));
        assertTrue(text.contains("Unknown help topic: unknown"));
        assertTrue(text.contains("flight"));
    }
}
