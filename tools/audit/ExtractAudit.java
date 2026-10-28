import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical fact extractor for the Swarmie primitive completeness audit.
 *
 * <p>Uses only the JDK 25 Class-File API. It extracts exact class/method
 * identities, field types, direct invocations and method code hashes from the
 * locally resolved Minecraft/Fabric JARs. It deliberately does not claim a
 * transitive call graph.
 */
public final class ExtractAudit {
    private static final String FORMAT = "swarmie-primitive-audit/1";
    private static final Pattern PASSIVE_METHOD = Pattern.compile(
            "^(tick|baseTick|aiStep|travel|move|collide|checkInsideBlocks|entityInside|"
                    + "playerTouch|hurt|hurtServer|attack|interact|take|drop|pickup|"
                    + "handlePortal|setAsInsidePortal|processPortalTeleportation|"
                    + "checkBelowWorld|isInWall|isInsidePortal)$");

    private static final List<String> PASSIVE_CLASSES = List.of(
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/player/Player",
            "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/world/entity/item/ItemEntity",
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
            "net/minecraft/world/level/block/BasePressurePlateBlock",
            "net/minecraft/world/level/block/PressurePlateBlock",
            "net/minecraft/world/level/block/TripWireBlock",
            "net/minecraft/world/entity/PortalProcessor",
            "net/minecraft/world/level/Level");

    private static final List<String> FABRIC_NETWORK_CLASSES = List.of(
            "net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry",
            "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking",
            "net/fabricmc/fabric/api/networking/v1/ServerConfigurationNetworking");

    private static final List<String> FABRIC_TRANSFER_CLASSES = List.of(
            "net/fabricmc/fabric/api/transfer/v1/item/ItemStorage",
            "net/fabricmc/fabric/api/transfer/v1/storage/Storage");

    private static final List<String> FABRIC_LOOKUP_CLASSES = List.of(
            "net/fabricmc/fabric/api/lookup/v1/block/BlockApiLookup");

    public static void main(String[] args) throws Exception {
        Path serverJar = null;
        Path clientJar = null;
        Path netJar = null;
        Path transferJar = null;
        Path lookupJar = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> serverJar = Path.of(args[++i]);
                case "--client" -> clientJar = Path.of(args[++i]);
                case "--fabric-net" -> netJar = Path.of(args[++i]);
                case "--fabric-transfer" -> transferJar = Path.of(args[++i]);
                case "--fabric-lookup" -> lookupJar = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (serverJar == null || clientJar == null || netJar == null
                || transferJar == null || lookupJar == null || out == null) {
            throw new IllegalArgumentException(
                    "usage: --server --client --fabric-net --fabric-transfer --fabric-lookup --out");
        }

        Minecraft mc = new Minecraft(serverJar);
        Fabric fabric = new Fabric(netJar, transferJar, lookupJar);

        List<Object> sources = new ArrayList<>();
        sources.addAll(serverbound(mc, "net/minecraft/network/protocol/game/", "SB.PLAY",
                "serverbound_play", List.of(
                        "net/minecraft/server/network/ServerGamePacketListenerImpl")));
        sources.addAll(serverbound(mc, "net/minecraft/network/protocol/common/", "SB.COMMON",
                "serverbound_common", List.of(
                        "net/minecraft/server/network/ServerCommonPacketListenerImpl",
                        "net/minecraft/server/network/ServerGamePacketListenerImpl",
                        "net/minecraft/server/network/ServerConfigurationPacketListenerImpl")));
        sources.addAll(clientbound(mc));
        sources.addAll(passive(mc));
        sources.addAll(extensions(fabric));

        sources.sort((a, b) -> ((Map<?, ?>) a).get("sourceId").toString()
                .compareTo(((Map<?, ?>) b).get("sourceId").toString()));

        Map<String, Object> root = obj();
        root.put("format", FORMAT);
        root.put("environment", environment());
        root.put("artifacts", List.of(
                artifact("minecraft-server", serverJar, readVersion(serverJar)),
                artifact("minecraft-client", clientJar, readVersion(clientJar)),
                artifact("fabric-networking-api-v1", netJar, versionFromName(netJar)),
                artifact("fabric-transfer-api-v1", transferJar, versionFromName(transferJar)),
                artifact("fabric-api-lookup-api-v1", lookupJar, versionFromName(lookupJar))));
        root.put("discovery", discovery(sources));
        root.put("sources", sources);
        root.put("limitations", List.of(
                "Direct invocations only; this is not a transitive call graph.",
                "Method fingerprints are SHA-256 of the raw Code attribute plus method descriptor; "
                        + "they are conservative review triggers, not constant-pool-normalized digests.",
                "Clientbound packet semantics are not classified here; they require human review.",
                "Extension-point signatures are parsed from the resolved Fabric JARs; runtime "
                        + "registrations (mod payloads/receivers) are not present in this environment.",
                "No modded block/item/entity implementations are enumerated."));

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " with " + sources.size() + " source entries");
    }

    // ------------------------------------------------------------------
    // Minecraft extraction
    // ------------------------------------------------------------------
    private static List<Object> serverbound(
            Minecraft mc, String prefix, String idPrefix, String category, List<String> implClasses)
            throws IOException {
        List<Object> out = new ArrayList<>();
        String handlerOwner = category.equals("serverbound_play")
                ? "net/minecraft/network/protocol/game/ServerGamePacketListener"
                : "net/minecraft/network/protocol/common/ServerCommonPacketListener";
        for (String name : mc.names(prefix, "Serverbound", false)) {
            ClassModel packet = mc.model(name);
            if (packet == null || !mc.implementsInterface(name, "net/minecraft/network/protocol/Packet")) {
                continue;
            }
            String simple = simpleName(name);
            String id = idPrefix + "." + upperSnake(trimPacket(simple));
            HandlerRef handler = handlerRef(packet);
            MethodFact implFact = handler == null
                    ? MethodFact.missing()
                    : findMethodInAny(mc, implClasses, handler.name(), handler.desc());
            String classHash = mc.hash(name);

            Map<String, Object> source = obj();
            source.put("sourceId", id);
            source.put("category", category);
            source.put("kind", "packet");
            source.put("class", dot(name));
            source.put("classSha256", classHash);
            source.put("handler", handlerMap(handler, implFact, handlerOwner));
            source.put("fields", fields(mc, packet));
            List<Object> variants = discriminators(mc, packet, id);
            source.put("discriminators", variants);
            source.put("fingerprint", implFact.found() ? implFact.fingerprint() : classHash);
            source.put("fingerprintKind", implFact.found() ? "handler_code_sha256" : "class_sha256");
            out.add(source);

            for (Object variant : variants) {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) variant;
                Map<String, Object> child = obj();
                child.put("sourceId", v.get("sourceId"));
                child.put("category", category);
                child.put("kind", "packet_variant");
                child.put("parentId", id);
                child.put("class", dot(name));
                child.put("field", v.get("field"));
                child.put("value", v.get("value"));
                child.put("fingerprint", source.get("fingerprint"));
                child.put("fingerprintKind", source.get("fingerprintKind"));
                out.add(child);
            }
        }
        return out;
    }

    private static List<Object> clientbound(Minecraft mc) throws IOException {
        List<Object> out = new ArrayList<>();
        for (String name : mc.names("net/minecraft/network/protocol/game/", "Clientbound", false)) {
            ClassModel packet = mc.model(name);
            if (packet == null || !mc.implementsInterface(name, "net/minecraft/network/protocol/Packet")) {
                continue;
            }
            HandlerRef handler = handlerRef(packet);
            String id = "CB.PLAY." + upperSnake(trimPacket(simpleName(name)));
            Map<String, Object> source = obj();
            source.put("sourceId", id);
            source.put("category", "clientbound_play");
            source.put("kind", "packet");
            source.put("class", dot(name));
            source.put("classSha256", mc.hash(name));
            Map<String, Object> handlerMap = obj();
            if (handler == null) {
                handlerMap.put("found", false);
            }
            else {
                handlerMap.put("owner", dot(handler.owner()));
                handlerMap.put("method", handler.name());
                handlerMap.put("descriptor", handler.desc());
                handlerMap.put("found", true);
            }
            source.put("handler", handlerMap);
            source.put("fields", fields(mc, packet));
            source.put("fingerprint", mc.hash(name));
            source.put("fingerprintKind", "class_sha256");
            out.add(source);
        }
        return out;
    }

    private static List<Object> passive(Minecraft mc) throws IOException {
        List<Object> out = new ArrayList<>();
        for (String className : PASSIVE_CLASSES) {
            ClassModel cm = mc.model(className);
            if (cm == null) {
                Map<String, Object> missing = obj();
                missing.put("sourceId", "PAS." + simpleName(className) + ".MISSING");
                missing.put("category", "passive_embodied");
                missing.put("kind", "class_seed");
                missing.put("class", dot(className));
                missing.put("found", false);
                missing.put("fingerprint", "");
                out.add(missing);
                continue;
            }
            for (MethodModel method : cm.methods()) {
                String methodName = method.methodName().stringValue();
                if (!PASSIVE_METHOD.matcher(methodName).matches()) {
                    continue;
                }
                String desc = method.methodType().stringValue();
                MethodFact fact = methodFact(cm, method, methodName, desc);
                Map<String, Object> source = obj();
                source.put("sourceId", "PAS." + simpleName(className) + "."
                        + methodName + "#" + shortHash(desc));
                source.put("category", "passive_embodied");
                source.put("kind", "method");
                source.put("class", dot(className));
                source.put("method", methodName);
                source.put("descriptor", desc);
                source.put("found", true);
                source.put("fingerprint", fact.fingerprint());
                source.put("fingerprintKind", "method_code_sha256");
                source.put("branches", fact.branches());
                source.put("invokedynamicCount", fact.invokedynamicCount());
                source.put("invokedynamic", fact.invokedynamic());
                source.put("calls", fact.calls());
                out.add(source);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Fabric extraction
    // ------------------------------------------------------------------
    private static List<Object> extensions(Fabric fabric) throws IOException {
        List<Object> out = new ArrayList<>();
        out.addAll(extensionClasses(fabric.net(), "networking", FABRIC_NETWORK_CLASSES));
        out.addAll(extensionClasses(fabric.transfer(), "transfer", FABRIC_TRANSFER_CLASSES));
        out.addAll(extensionClasses(fabric.lookup(), "lookup", FABRIC_LOOKUP_CLASSES));
        return out;
    }

    private static List<Object> extensionClasses(JarIndex jar, String domain, List<String> classes)
            throws IOException {
        List<Object> out = new ArrayList<>();
        for (String className : classes) {
            ClassModel cm = jar.model(className);
            if (cm == null) {
                Map<String, Object> missing = obj();
                missing.put("sourceId", "EXT.FABRIC." + upperSnake(simpleName(className)) + ".MISSING");
                missing.put("category", "fabric_extension");
                missing.put("kind", "class_seed");
                missing.put("class", dot(className));
                missing.put("found", false);
                missing.put("fingerprint", "");
                out.add(missing);
                continue;
            }
            String classHash = jar.hash(className);
            for (MethodModel method : cm.methods()) {
                if (method.flags().has(AccessFlag.SYNTHETIC)
                        || method.flags().has(AccessFlag.PRIVATE)) {
                    continue;
                }
                String mName = method.methodName().stringValue();
                if (mName.startsWith("<") || mName.startsWith("lambda$")) {
                    continue;
                }
                String desc = method.methodType().stringValue();
                Map<String, Object> source = obj();
                source.put("sourceId", "EXT.FABRIC." + upperSnake(simpleName(className))
                        + "." + mName + "#" + shortHash(desc));
                source.put("category", "fabric_extension");
                source.put("kind", "method");
                source.put("domain", domain);
                source.put("class", dot(className));
                source.put("method", mName);
                source.put("descriptor", desc);
                source.put("found", true);
                source.put("fingerprint", classHash);
                source.put("fingerprintKind", "class_sha256");
                out.add(source);
            }
            for (FieldModel field : cm.fields()) {
                if (field.flags().has(AccessFlag.SYNTHETIC)
                        || field.flags().has(AccessFlag.PRIVATE)) {
                    continue;
                }
                Map<String, Object> source = obj();
                source.put("sourceId", "EXT.FABRIC." + upperSnake(simpleName(className))
                        + "." + field.fieldName().stringValue());
                source.put("category", "fabric_extension");
                source.put("kind", "field");
                source.put("domain", domain);
                source.put("class", dot(className));
                source.put("field", field.fieldName().stringValue());
                source.put("type", field.fieldType().stringValue());
                source.put("found", true);
                source.put("fingerprint", classHash);
                source.put("fingerprintKind", "class_sha256");
                out.add(source);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Class facts
    // ------------------------------------------------------------------
    private record HandlerRef(String owner, String name, String desc) {
    }

    private record MethodFact(boolean found, String owner, String name, String desc,
                              int branches, int invokedynamicCount, List<String> calls,
                              List<String> invokedynamic, String fingerprint) {
        static MethodFact missing() {
            return new MethodFact(false, "", "", "", 0, 0, List.of(), List.of(), "");
        }
    }

    private static HandlerRef handlerRef(ClassModel packet) {
        for (MethodModel method : packet.methods()) {
            if (!method.methodName().stringValue().equals("handle")) {
                continue;
            }
            String desc = method.methodType().stringValue();
            if (desc.equals("(Lnet/minecraft/network/PacketListener;)V")) {
                continue;
            }
            for (CodeElement element : method.code().orElseThrow()) {
                if (element instanceof InvokeInstruction invoke
                        && invoke.name().stringValue().startsWith("handle")) {
                    return new HandlerRef(invoke.owner().asInternalName(),
                            invoke.name().stringValue(), invoke.type().stringValue());
                }
            }
        }
        return null;
    }

    private static Map<String, Object> handlerMap(HandlerRef handler, MethodFact fact, String interfaceName) {
        Map<String, Object> map = obj();
        map.put("interface", dot(handler == null ? interfaceName : handler.owner()));
        map.put("method", handler == null ? "" : handler.name());
        map.put("descriptor", handler == null ? "" : handler.desc());
        map.put("implementation", fact.found() ? dot(fact.owner()) : "");
        map.put("found", fact.found());
        map.put("fingerprint", fact.found() ? fact.fingerprint() : "");
        map.put("branches", fact.found() ? fact.branches() : 0);
        map.put("invokedynamicCount", fact.found() ? fact.invokedynamicCount() : 0);
        map.put("invokedynamic", fact.found() ? fact.invokedynamic() : List.of());
        map.put("calls", fact.found() ? fact.calls() : List.of());
        return map;
    }

    private static List<Object> fields(Minecraft mc, ClassModel packet) throws IOException {
        List<Object> out = new ArrayList<>();
        for (FieldModel field : packet.fields()) {
            if (field.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            Map<String, Object> map = obj();
            map.put("name", field.fieldName().stringValue());
            map.put("type", field.fieldType().stringValue());
            map.put("static", field.flags().has(AccessFlag.STATIC));
            out.add(map);
        }
        return out;
    }

    private static List<Object> discriminators(Minecraft mc, ClassModel packet, String packetId)
            throws IOException {
        List<Object> out = new ArrayList<>();
        for (FieldModel field : packet.fields()) {
            if (field.flags().has(AccessFlag.STATIC) || field.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            String fieldName = field.fieldName().stringValue();
            // Only action-like enums are operation discriminators. Hand, direction,
            // difficulty and similar fields are parameters, not separate primitives.
            if (!fieldName.equals("action") && !fieldName.equals("containerInput")) {
                continue;
            }
            String type = field.fieldType().stringValue();
            if (!type.startsWith("L") || !type.endsWith(";")) {
                continue;
            }
            String internal = type.substring(1, type.length() - 1);
            ClassModel enumClass = mc.model(internal);
            if (enumClass == null || !isEnum(enumClass)) {
                continue;
            }
            for (FieldModel constant : enumClass.fields()) {
                if (!constant.flags().has(AccessFlag.ENUM)) {
                    continue;
                }
                Map<String, Object> map = obj();
                map.put("field", fieldName);
                map.put("value", constant.fieldName().stringValue());
                map.put("sourceId", packetId + "." + upperSnake(fieldName) + "."
                        + constant.fieldName().stringValue());
                out.add(map);
            }
        }
        return out;
    }

    private static boolean isEnum(ClassModel cm) {
        return cm.superclass().map(s -> s.asInternalName().equals("java/lang/Enum")).orElse(false);
    }

    private static MethodFact findMethodInAny(
            Minecraft mc, List<String> implClasses, String name, String desc) throws IOException {
        for (String implClass : implClasses) {
            ClassModel impl = mc.model(implClass);
            if (impl == null) {
                continue;
            }
            MethodFact fact = findMethod(mc, impl, name, desc);
            if (fact.found()) {
                return fact;
            }
        }
        return MethodFact.missing();
    }

    private static MethodFact findMethod(Minecraft mc, ClassModel start, String name, String desc)
            throws IOException {
        ClassModel current = start;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current.thisClass().asInternalName())) {
            for (MethodModel method : current.methods()) {
                if (method.methodName().stringValue().equals(name)
                        && method.methodType().stringValue().equals(desc)) {
                    return methodFact(current, method, name, desc);
                }
            }
            current = current.superclass().map(s -> mc.modelQuiet(s.asInternalName())).orElse(null);
        }
        return MethodFact.missing();
    }

    private static MethodFact methodFact(ClassModel owner, MethodModel method, String name, String desc)
            throws IOException {
        List<String> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> invokedynamic = new ArrayList<>();
        Set<String> indySeen = new HashSet<>();
        int indyCount = 0;
        int branches = 0;
        Optional<CodeAttribute> code = method.findAttribute(java.lang.classfile.Attributes.code());
        if (code.isPresent()) {
            for (CodeElement element : code.get()) {
                if (element instanceof InvokeInstruction invoke) {
                    String call = invoke.opcode().name() + " " + dot(invoke.owner().asInternalName())
                            + "." + invoke.name().stringValue() + invoke.type().stringValue();
                    if (seen.add(call)) {
                        calls.add(call);
                    }
                }
                if (element instanceof InvokeDynamicInstruction indy) {
                    indyCount++;
                    String entry = indy.name().stringValue() + " -> "
                            + indy.bootstrapMethod().toString();
                    if (indySeen.add(entry)) {
                        invokedynamic.add(entry);
                    }
                }
                if (element instanceof BranchInstruction
                        || element instanceof TableSwitchInstruction
                        || element instanceof LookupSwitchInstruction) {
                    branches++;
                }
            }
        }
        String fingerprint = fingerprint(desc, code.orElse(null));
        return new MethodFact(true, owner.thisClass().asInternalName(), name, desc,
                branches, indyCount, List.copyOf(calls), List.copyOf(invokedynamic), fingerprint);
    }

    private static String fingerprint(String desc, CodeAttribute code) {
        MessageDigest digest = sha256();
        digest.update(desc.getBytes(StandardCharsets.UTF_8));
        if (code != null) {
            digest.update(code.codeArray());
        }
        return hex(digest.digest());
    }

    // ------------------------------------------------------------------
    // Jar index
    // ------------------------------------------------------------------
    private static final class JarIndex {
        final Path path;
        final Map<String, byte[]> bytes = new TreeMap<>();
        final Map<String, ClassModel> models = new TreeMap<>();

        JarIndex(Path path) throws IOException {
            this.path = path;
            try (JarFile jar = new JarFile(path.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")
                            || name.equals("module-info.class")) {
                        continue;
                    }
                    bytes.put(name.substring(0, name.length() - 6),
                            jar.getInputStream(entry).readAllBytes());
                }
            }
        }

        ClassModel model(String internalName) throws IOException {
            if (!bytes.containsKey(internalName)) {
                return null;
            }
            ClassModel model = models.get(internalName);
            if (model == null) {
                model = ClassFile.of().parse(bytes.get(internalName));
                models.put(internalName, model);
            }
            return model;
        }

        ClassModel modelQuiet(String internalName) {
            try {
                return model(internalName);
            }
            catch (IOException exception) {
                return null;
            }
        }

        String hash(String internalName) {
            byte[] data = bytes.get(internalName);
            return data == null ? "" : hex(sha256().digest(data));
        }

        List<String> names(String prefix, String simpleStartsWith, boolean nested) {
            List<String> out = new ArrayList<>();
            for (String name : bytes.keySet()) {
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String simple = name.substring(prefix.length());
                if (!nested && simple.contains("$")) {
                    continue;
                }
                if (!simple.startsWith(simpleStartsWith)) {
                    continue;
                }
                out.add(name);
            }
            return out;
        }
    }

    private static final class Minecraft {
        final JarIndex index;
        final Map<String, Boolean> packetCache = new TreeMap<>();

        Minecraft(Path jar) throws IOException {
            this.index = new JarIndex(jar);
        }

        ClassModel model(String name) throws IOException {
            return index.model(name);
        }

        ClassModel modelQuiet(String name) {
            return index.modelQuiet(name);
        }

        String hash(String name) {
            return index.hash(name);
        }

        List<String> names(String prefix, String startsWith, boolean nested) {
            return index.names(prefix, startsWith, nested);
        }

        boolean implementsInterface(String name, String iface) throws IOException {
            String key = name + "|" + iface;
            Boolean cached = packetCache.get(key);
            if (cached != null) {
                return cached;
            }
            boolean result = implementsInterface(index.model(name), iface, new HashSet<>());
            packetCache.put(key, result);
            return result;
        }

        private boolean implementsInterface(ClassModel cm, String iface, Set<String> seen) {
            if (cm == null || !seen.add(cm.thisClass().asInternalName())) {
                return false;
            }
            for (var i : cm.interfaces()) {
                if (i.asInternalName().equals(iface)) {
                    return true;
                }
                if (implementsInterface(index.modelQuiet(i.asInternalName()), iface, seen)) {
                    return true;
                }
            }
            return cm.superclass()
                    .map(s -> implementsInterface(index.modelQuiet(s.asInternalName()), iface, seen))
                    .orElse(false);
        }
    }

    private static final class Fabric {
        final JarIndex net;
        final JarIndex transfer;
        final JarIndex lookup;

        Fabric(Path net, Path transfer, Path lookup) throws IOException {
            this.net = new JarIndex(net);
            this.transfer = new JarIndex(transfer);
            this.lookup = new JarIndex(lookup);
        }

        JarIndex net() {
            return net;
        }

        JarIndex transfer() {
            return transfer;
        }

        JarIndex lookup() {
            return lookup;
        }
    }

    // ------------------------------------------------------------------
    // Metadata and JSON
    // ------------------------------------------------------------------
    private static Map<String, Object> environment() {
        Map<String, Object> env = obj();
        env.put("minecraft", "26.3");
        env.put("fabricLoader", "0.20.1");
        env.put("fabricApi", "0.165.0+26.3");
        env.put("loom", "1.19.3");
        env.put("gradle", "9.8.2");
        env.put("java", System.getProperty("java.version"));
        env.put("javaVendor", System.getProperty("java.vendor"));
        env.put("mapping", "none; Minecraft 26.3 ships unobfuscated and Loom uses no-remap");
        env.put("extractor", "tools/audit/ExtractAudit.java");
        env.put("extractorJdkApi", "java.lang.classfile (JDK 25)");
        return env;
    }

    private static Map<String, Object> discovery(List<Object> sources) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Object source : sources) {
            String category = ((Map<?, ?>) source).get("category").toString();
            counts.merge(category, 1, Integer::sum);
        }
        Map<String, Object> discovery = obj();
        discovery.put("method", "JDK 25 Class-File API over locally resolved JARs");
        discovery.put("counts", counts);
        discovery.put("note", "Counts include packet variants and extension methods/fields.");
        return discovery;
    }

    private static Map<String, Object> artifact(String role, Path path, String version) throws IOException {
        Map<String, Object> map = obj();
        map.put("role", role);
        map.put("fileName", path.getFileName().toString());
        map.put("version", version);
        map.put("size", Files.size(path));
        map.put("sha256", hex(sha256().digest(Files.readAllBytes(path))));
        return map;
    }

    private static String readVersion(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("version.json");
            if (entry == null) {
                return "unknown";
            }
            String text = new String(jf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
            return matcher.find() ? matcher.group(1) : "unknown";
        }
    }

    private static String versionFromName(Path jar) {
        Matcher matcher = Pattern.compile("-([0-9][^/]*)\\.jar$").matcher(jar.getFileName().toString());
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static String simpleName(String internalName) {
        int index = internalName.lastIndexOf('/');
        return index < 0 ? internalName : internalName.substring(index + 1);
    }

    private static String dot(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String trimPacket(String simple) {
        String value = simple;
        if (value.startsWith("Serverbound")) {
            value = value.substring("Serverbound".length());
        }
        if (value.startsWith("Clientbound")) {
            value = value.substring("Clientbound".length());
        }
        if (value.endsWith("Packet")) {
            value = value.substring(0, value.length() - "Packet".length());
        }
        return value;
    }

    private static String upperSnake(String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && out.length() > 0
                    && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    private static String shortHash(String text) {
        return hex(sha256().digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static Map<String, Object> obj() {
        return new TreeMap<>();
    }

    private static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        }
        else if (value instanceof String text) {
            quote(text, sb);
        }
        else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        }
        else if (value instanceof Map<?, ?> map) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pad(sb, indent + 1);
                quote(entry.getKey().toString(), sb);
                sb.append(": ");
                write(entry.getValue(), sb, indent + 1);
                if (++i < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        }
        else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                write(list.get(i), sb, indent + 1);
                if (i + 1 < list.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        }
        else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
        }
    }

    private static void quote(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private ExtractAudit() {
    }
}
