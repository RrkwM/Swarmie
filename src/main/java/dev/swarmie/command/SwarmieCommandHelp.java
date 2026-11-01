package dev.swarmie.command;

import java.util.List;

/**
 * Plain text for the in-game debug command help. Deliberately kept free of
 * Minecraft/Fabric types so the command descriptions can be unit-tested.
 */
final class SwarmieCommandHelp {
    static final List<String> TOPICS = List.of(
            "selection", "flight", "actions", "inventory", "query", "adapter", "lifecycle");

    private SwarmieCommandHelp() {
    }

    static List<String> lines(String topic) {
        return switch (topic) {
            case "" -> root();
            case "selection" -> selection();
            case "flight" -> flight();
            case "actions" -> actions();
            case "inventory" -> inventory();
            case "query" -> query();
            case "adapter" -> adapter();
            case "lifecycle" -> lifecycle();
            default -> List.of(
                    "Unknown help topic: " + topic,
                    "Use /swarmie help, or one of: " + String.join(", ", TOPICS));
        };
    }

    private static List<String> root() {
        return List.of(
                "Swarmie admin/debug commands (OP permission level 2+).",
                "These are debug commands, not the public JSONL/Python API.",
                "/swarmie help [topic]",
                "  topics: " + String.join(", ", TOPICS),
                "/swarmie list                            loaded robots, numbered",
                "/swarmie select <index|uuid>              select a robot",
                "/swarmie selected                        show current selection",
                "/swarmie spawn                           create/select a robot in front",
                "/swarmie remove                          remove selected robot",
                "/swarmie fly [targets] <x> <y> <z> <duration>",
                "/swarmie stop [targets]                  omit targets for selection",
                "/swarmie velocity <targets> <x> <y> <z>  DEBUG: bypasses Runtime",
                "/swarmie status [target]                 omit target for selection",
                "/swarmie results [target]                latest five results",
                "/swarmie cancel|cancelid|cancelall       cancel work",
                "/swarmie mine <target> <x> <y> <z>       native break",
                "/swarmie use <target> <x> <y> <z> <face> native use-on-block",
                "/swarmie inventory give|show ...         admin/debug inventory",
                "/swarmie query block|region ...          read-only world query",
                "/swarmie sensor scan <target> <range>    read-only local sensor",
                "/swarmie adapter ...                     development diagnostics",
                "DCPU/MMIO is test-only and has no in-game command.");
    }

    private static List<String> selection() {
        return List.of(
                "/swarmie list",
                "  Lists loaded robots by index, UUID and position.",
                "/swarmie select <index|uuid>",
                "  Selects by list index, full UUID or unique UUID prefix.",
                "/swarmie selected",
                "  Shows the current selection.",
                "/swarmie spawn",
                "  Creates a robot two blocks in front of the source and selects it.",
                "/swarmie remove",
                "  Discards the selected robot.",
                "",
                "Short forms: /swarmie fly <x> <y> <z> <duration>, /swarmie stop,",
                "/swarmie status and /swarmie results all use the selected robot.");
    }

    private static List<String> flight() {
        return List.of(
                "/swarmie fly <x> <y> <z> <duration>       selected robot",
                "/swarmie fly <targets> <x> <y> <z> <duration>",
                "  x/y/z: velocity in blocks/tick; duration: 1..6000 ticks.",
                "  Queued in Runtime; decelerates after the duration.",
                "  This is velocity, not move-to-coordinates or pathfinding.",
                "",
                "/swarmie stop                              selected robot",
                "/swarmie stop <targets>",
                "  Queues a controlled stop and waits for low native speed.",
                "",
                "/swarmie velocity <targets> <x> <y> <z>",
                "  DEBUG: sets the flight target directly and bypasses the Runtime",
                "  queue/results. Use fly/stop for normal operations.");
    }

    private static List<String> actions() {
        return List.of(
                "/swarmie mine <target> <x> <y> <z>",
                "  Native block break; target must be loaded and within 6 blocks.",
                "  Uses the selected inventory slot; drops are not auto-collected.",
                "",
                "/swarmie use <target> <x> <y> <z> <face>",
                "  face: down/up/north/south/east/west.",
                "  Native use-on-block with the selected slot.",
                "  GUI containers are not opened; native success is not proof of",
                "  a container/item side effect. Use state/inventory observation.");
    }

    private static List<String> inventory() {
        return List.of(
                "/swarmie inventory show <target>",
                "  Shows the nine slots; '*' marks the selected slot.",
                "",
                "/swarmie inventory give <target> <item> <count>",
                "  Admin/debug insertion only; this is not a normal gameplay API.",
                "  Giving an item does not necessarily select its slot.");
    }

    private static List<String> query() {
        return List.of(
                "/swarmie query block <x> <y> <z>",
                "  Read-only block query; unloaded chunks are not force-loaded.",
                "",
                "/swarmie query region <x1> <y1> <z1> <x2> <y2> <z2>",
                "  Read-only bounded region query; unavailable positions are not air.",
                "",
                "/swarmie sensor scan <target> <range>",
                "  Read-only local sensor; max 64 results; LOS is not supported.");
    }

    private static List<String> adapter() {
        return List.of(
                "/swarmie adapter status",
                "/swarmie adapter events <after> <max>",
                "/swarmie adapter submit <target> stop <correlation>",
                "/swarmie adapter submit <target> fly <x> <y> <z> <duration> <correlation>",
                "  Development diagnostics for the public Adapter path.",
                "  Not a general player or DCPU control interface.");
    }

    private static List<String> lifecycle() {
        return List.of(
                "/swarmie status <target>",
                "UUID and inventory survive chunk unload/reload.",
                "Active/queued commands are INTERRUPTED on unload and do not resume.",
                "Removal marks the robot REMOVED; a new entity may reuse the UUID.",
                "Dimension changes are not permanent deletion.",
                "Command results and events do not survive a server restart.");
    }
}
