package dev.swarmie.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwarmieCommandRegistrationTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void helpAndItsTopicsAreRegisteredUnderSwarmie() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SwarmieCommands.register(dispatcher);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("swarmie");
        assertNotNull(root);
        CommandNode<CommandSourceStack> help = root.getChild("help");
        assertNotNull(help);
        for (String topic : SwarmieCommandHelp.TOPICS) {
            assertNotNull(help.getChild(topic), "missing help topic " + topic);
        }
        assertNotNull(root.getChild("fly"));
        assertNotNull(root.getChild("inventory"));
        assertNotNull(root.getChild("adapter"));
        assertNotNull(root.getChild("list"));
        assertNotNull(root.getChild("select"));
        assertNotNull(root.getChild("selected"));
        assertNotNull(root.getChild("spawn"));
        assertNotNull(root.getChild("remove"));
        CommandNode<CommandSourceStack> fly = root.getChild("fly");
        assertNotNull(fly.getChild("x"), "selected-robot fly form");
        assertNotNull(fly.getChild("targets"), "selector fly form");
        assertNotNull(root.getChild("stop").getCommand(), "selected-robot stop form");
        assertNotNull(root.getChild("status").getCommand(), "selected-robot status form");
    }
}
