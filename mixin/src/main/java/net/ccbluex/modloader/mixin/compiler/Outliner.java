package net.ccbluex.modloader.mixin.compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Splits a mixed class into what the loaded class may become and a companion for the rest. A loaded class
 * keeps its fields, methods and interfaces; only method bodies may change. So a method the mixins changed
 * keeps its place and calls a static copy of its new body in the companion, and a method they added exists
 * only there.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class Outliner {

    /**
     * @param target the baseline with its changed methods calling the companion
     * @param interfaces internal names of the interfaces the mixins add
     * @param methods name and descriptor of every method the mixins added, to the companion's
     * @param initializer companion method holding what the mixins add to the static initializer, or null
     * @param fields name of every instance field the mixins add with an initializer, to the companion method that
     *               computes its first value for an object
     * @param skipped what the mixins ask for that had to be left out, for the person loading the mod
     */
    record Outline(ClassNode target, ClassNode companion, List<String> interfaces, Map<String, MethodNode> methods,
            String initializer, Map<String, String> fields, List<String> skipped) {
    }

    private final ClassFiles classes;
    private final ClassNode baseline;
    private final ClassNode companion = new ClassNode();
    private final Map<String, MethodNode> added = new LinkedHashMap<>();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private String initializer;

    private Outliner(ClassFiles classes, ClassNode baseline, String companionName) {
        this.classes = classes;
        this.baseline = baseline;
        companion.version = baseline.version;
        companion.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        companion.name = companionName;
        companion.superName = "java/lang/Object";
        companion.sourceFile = baseline.sourceFile;
    }

    /**
     * @param classes class files of the game and the mods
     * @param baseline the class as the game defined it, read with expanded frames; its own Mixin may have changed it
     * @param mixed the same class after Mixin, read with expanded frames
     * @param isRelaxed whether a mod outside its own framework mixes into the class: then a change of a method that
     *                  a loaded class cannot take is left out, and the method stays as the game defined it
     * @throws UnsupportedOperationException when the mixins ask for what a loaded class cannot do
     */
    static Outline outline(ClassFiles classes, ClassNode baseline, ClassNode mixed, String companionName, boolean isRelaxed,
            boolean isInitialized) {
        Outliner outliner = new Outliner(classes, baseline, companionName);
        rename(baseline, mixed);
        outliner.initializeFields(mixed);
        Map<String, MethodNode> unchanged = new LinkedHashMap<>();
        baseline.methods.forEach(method -> unchanged.put(method.name + method.desc, method));
        List<MethodNode> methods = new ArrayList<>();
        for (MethodNode method : mixed.methods) {
            MethodNode before = unchanged.remove(method.name + method.desc);
            int moved = outliner.companion.methods.size();
            try {
                if (method.name.equals("<clinit>") && (isInitialized || before == null)) {
                    outliner.initialize(before, method);
                    if (before != null) {
                        methods.add(before);
                    }
                } else if (before == null) {
                    if (method.instructions.size() > 0) {
                        outliner.add(method);
                    }
                } else if (code(before, 0, before.instructions.size()).equals(code(method, 0, method.instructions.size()))) {
                    methods.add(before);
                } else if (method.name.equals("<init>")) {
                    methods.add(outliner.splitConstructor(before, method));
                } else {
                    methods.add(outliner.trampoline(before, outliner.move(method, method.name, method.instructions, method.tryCatchBlocks)));
                }
            } catch (UnsupportedOperationException e) {
                if (!isRelaxed) {
                    throw e;
                }
                // Each step refuses before it changes the method; what it moved already goes.
                outliner.companion.methods.subList(moved, outliner.companion.methods.size()).clear();
                outliner.skipped.add(e.getMessage());
                if (before != null) {
                    methods.add(before);
                }
            }
        }
        methods.addAll(unchanged.values());
        baseline.methods = methods;
        workAroundJdk8376185(baseline);

        List<String> interfaces = new ArrayList<>(mixed.interfaces);
        interfaces.removeAll(baseline.interfaces);
        return new Outline(baseline, outliner.companion, interfaces, outliner.added, outliner.initializer, outliner.fields, outliner.skipped);
    }

    private static void workAroundJdk8376185(ClassNode redefined) {
        if (redefined.recordComponents != null) {
            for (RecordComponentNode component : redefined.recordComponents) {
                component.visibleTypeAnnotations = null;
                component.invisibleTypeAnnotations = null;
            }
        }
    }

    /**
     * An object that existed before the mixins never ran the initializers of the fields they add, which Mixin put
     * into every constructor that calls super. Each one that only reads the object becomes a companion method from
     * the object to the value, which Linker runs on the first read instead.
     */
    private void initializeFields(ClassNode mixed) {
        Set<String> existing = new HashSet<>();
        baseline.fields.forEach(field -> existing.add(field.name + field.desc));
        for (MethodNode constructor : mixed.methods) {
            int cut = constructor.name.equals("<init>") ? superCall(constructor) : -1;
            if (cut < 0 || ((MethodInsnNode) constructor.instructions.get(cut)).owner.equals(mixed.name)) {
                continue;
            }
            AbstractInsnNode[] code = constructor.instructions.toArray();
            // Mixin puts them after the last constructor call of the class or its superclass, which is later than the
            // super call when the constructor makes another object of the class, as NeoForge's Pack does.
            int start = cut + 1;
            for (int i = code.length - 1; i > cut; i--) {
                if (code[i] instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                        && (call.owner.equals(mixed.name) || call.owner.equals(mixed.superName))) {
                    start = i + 1;
                    break;
                }
            }
            for (int i = start; i < code.length; i++) {
                // Each initializer is the object, its value and the put; the class's own come along in between.
                if (code[i] instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(mixed.name)) {
                    MethodNode value = existing.contains(put.name + put.desc) || fields.containsKey(put.name) ? null
                            : fieldValue(constructor, code, start, i, put);
                    if (value != null) {
                        fields.put(put.name, value.name);
                    }
                    start = i + 1;
                }
            }
            // Mixin puts the same initializers into every constructor that calls super.
            return;
        }
    }

    /** The code from {@code start} to the put as a method, null when it does more than compute from the object. */
    private MethodNode fieldValue(MethodNode constructor, AbstractInsnNode[] code, int start, int put, FieldInsnNode field) {
        while (start < put && code[start].getOpcode() < 0) {
            start++;
        }
        if (!(code[start] instanceof VarInsnNode receiver && receiver.getOpcode() == Opcodes.ALOAD && receiver.var == 0)) {
            return null;
        }
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (int i = start; i < put; i++) {
            if (code[i] instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.name + "$" + companion.methods.size(),
                "(L" + baseline.name + ";)" + field.desc, null, null);
        for (int i = start + 1; i < put; i++) {
            AbstractInsnNode instruction = code[i];
            boolean isForeign = switch (instruction) {
                case VarInsnNode variable -> variable.getOpcode() != Opcodes.ALOAD || variable.var != 0;
                case IincInsnNode _ -> true;
                case FieldInsnNode other -> other.getOpcode() == Opcodes.PUTFIELD || other.getOpcode() == Opcodes.PUTSTATIC;
                case JumpInsnNode jump -> !labels.containsKey(jump.label);
                case TableSwitchInsnNode _, LookupSwitchInsnNode _ -> true;
                default -> instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN
                        || instruction.getOpcode() == Opcodes.ATHROW;
            };
            if (isForeign) {
                return null;
            }
            if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) {
                value.instructions.add(instruction.clone(labels));
            }
        }
        for (TryCatchBlockNode block : constructor.tryCatchBlocks) {
            if (labels.containsKey(block.start) || labels.containsKey(block.handler)) {
                return null;
            }
        }
        value.instructions.add(new InsnNode(Type.getType(field.desc).getOpcode(Opcodes.IRETURN)));
        value.maxStack = constructor.maxStack;
        value.maxLocals = 1;
        try {
            new Analyzer<>(new BasicVerifier()).analyze(companion.name, value);
        } catch (AnalyzerException e) {
            // The class's own code between two initializers came along, and it does not compute a value alone.
            return null;
        }
        // The frames of the constructor have the object under every value, which the method does not.
        value = recompute(value);
        companion.methods.add(value);
        return value;
    }

    /**
     * Mixin names what it merges into a class after counters and the id of its run, so the members the game's own
     * Mixin merged into the loaded class have other names in {@code mixed}. Gives them back the loaded names, so
     * that code nobody changed compares equal and keeps reaching the loaded fields.
     */
    private static void rename(ClassNode loaded, ClassNode mixed) {
        Map<String, String> methods = names(
                loaded.methods.stream().map(method -> List.of(method.name, method.desc, merger(method))).toList(),
                mixed.methods.stream().map(method -> List.of(method.name, method.desc, merger(method))).toList());
        Map<String, String> fields = names(
                loaded.fields.stream().map(field -> List.of(field.name, field.desc, "")).toList(),
                mixed.fields.stream().map(field -> List.of(field.name, field.desc, "")).toList());
        if (methods.isEmpty() && fields.isEmpty()) {
            return;
        }
        mixed.methods.forEach(method -> method.name = methods.getOrDefault(method.name + method.desc, method.name));
        mixed.fields.forEach(field -> field.name = fields.getOrDefault(field.name + field.desc, field.name));
        for (MethodNode method : mixed.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                switch (instruction) {
                    case MethodInsnNode call when call.owner.equals(mixed.name) ->
                            call.name = methods.getOrDefault(call.name + call.desc, call.name);
                    case FieldInsnNode field when field.owner.equals(mixed.name) ->
                            field.name = fields.getOrDefault(field.name + field.desc, field.name);
                    case InvokeDynamicInsnNode dynamic -> {
                        for (int i = 0; i < dynamic.bsmArgs.length; i++) {
                            if (dynamic.bsmArgs[i] instanceof Handle handle && handle.getOwner().equals(mixed.name)) {
                                Map<String, String> names = handle.getTag() <= Opcodes.H_PUTSTATIC ? fields : methods;
                                dynamic.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(),
                                        names.getOrDefault(handle.getName() + handle.getDesc(), handle.getName()), handle.getDesc(), handle.isInterface());
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    /**
     * For members (name, descriptor, merging mixin) only {@code mixed} has, by name and descriptor, the name of the
     * one member only {@code loaded} has that differs from it in the parts Mixin counts.
     */
    private static Map<String, String> names(List<List<String>> loaded, List<List<String>> mixed) {
        Set<String> loadedKeys = new HashSet<>();
        loaded.forEach(member -> loadedKeys.add(member.get(0) + member.get(1)));
        Set<String> mixedKeys = new HashSet<>();
        mixed.forEach(member -> mixedKeys.add(member.get(0) + member.get(1)));
        Map<List<String>, List<String>> onlyLoaded = new HashMap<>();
        for (List<String> member : loaded) {
            if (!mixedKeys.contains(member.get(0) + member.get(1))) {
                onlyLoaded.merge(List.of(uncounted(member.get(0)), member.get(1), member.get(2)), List.of(member.get(0)), Outliner::ambiguous);
            }
        }
        Map<List<String>, List<String>> onlyMixed = new HashMap<>();
        for (List<String> member : mixed) {
            if (!loadedKeys.contains(member.get(0) + member.get(1))) {
                onlyMixed.merge(List.of(uncounted(member.get(0)), member.get(1), member.get(2)), List.of(member.get(0)), Outliner::ambiguous);
            }
        }
        Map<String, String> names = new HashMap<>();
        onlyMixed.forEach((key, name) -> {
            List<String> loadedName = onlyLoaded.get(key);
            if (name.size() == 1 && loadedName != null && loadedName.size() == 1 && !name.equals(loadedName)) {
                names.put(name.getFirst() + key.get(1), loadedName.getFirst());
            }
        });
        return names;
    }

    private static List<String> ambiguous(List<String> first, List<String> second) {
        return List.of();
    }

    /** The name without the counters and the run id Mixin puts into handler, unique method and unique field names. */
    private static String uncounted(String name) {
        return name.replaceAll("\\$[a-z]{3,}[0-9a-f]{3,}\\$", "\\$")
                .replaceFirst("^(md|fd)[0-9a-f]+\\$(.+)\\$[0-9a-f]+$", "$1\\$$2")
                .replaceFirst("^(.+)_\\$md\\$[0-9a-f]+\\$[0-9a-f]+$", "$1_\\$md")
                .replaceFirst("^(.*mixinextras\\$.*?)(\\$\\d+)+$", "$1");
    }

    /** The mixin that merged the method, from the annotation Mixin leaves on it; empty for the class's own. */
    private static String merger(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations == null ? List.<AnnotationNode>of() : annotations) {
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) {
                    return String.valueOf(annotation.values.get(annotation.values.indexOf("mixin") + 1));
                }
            }
        }
        return "";
    }

    private void add(MethodNode method) {
        boolean isInherited = (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
        ClassFiles.Member overridden = null;
        if (isInherited) {
            overridden = classes.method(baseline.superName, method.name, method.desc);
            for (String implemented : baseline.interfaces) {
                if (overridden == null) {
                    overridden = classes.method(implemented, method.name, method.desc);
                }
            }
        }
        if (overridden != null && (overridden.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
            // The JVM dispatches by the methods a class has; one that is not there never gets the call.
            throw new UnsupportedOperationException("a mixin overrides " + overridden.declaring().name.replace('/', '.') + "."
                    + method.name + " in " + baseline.name.replace('/', '.') + ", which a loaded class cannot take");
        }
        added.put(method.name + method.desc, move(method, method.name, method.instructions, method.tryCatchBlocks));
    }

    /** Puts a static copy of the method into the companion, with these instructions for its body. */
    private MethodNode move(MethodNode method, String name, InsnList instructions, List<TryCatchBlockNode> tryCatchBlocks) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        MethodNode moved = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | (method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_VARARGS)),
                name.replaceAll("[<>]", "") + "$" + companion.methods.size(),
                isStatic ? method.desc : "(L" + baseline.name + ";" + method.desc.substring(1),
                null,
                method.exceptions.toArray(String[]::new));
        moved.instructions = instructions;
        moved.tryCatchBlocks = tryCatchBlocks;
        moved.maxStack = method.maxStack;
        moved.maxLocals = method.maxLocals;
        companion.methods.add(moved);
        return moved;
    }

    /** Leaves the method in place with a body that only calls its copy in the companion. */
    private MethodNode trampoline(MethodNode method, MethodNode moved) {
        method.instructions = new InsnList();
        method.tryCatchBlocks = new ArrayList<>();
        call(method, moved);
        return method;
    }

    /** Appends a call of the companion method with the method's own arguments, and the return. */
    private void call(MethodNode method, MethodNode moved) {
        // The debug tables name labels of the body that is gone.
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        int local = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, local++));
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            method.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        method.instructions.add(new InvokeDynamicInsnNode(moved.name, moved.desc, Rewriter.LINK, "companion", companion.name.replace('/', '.')));
        method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.maxStack = Math.max(method.maxStack, Math.max(local, 2));
        method.maxLocals = Math.max(method.maxLocals, local);
    }

    /**
     * A constructor has to call its super constructor itself. What follows that call runs on an initialized
     * object like any method, so that part moves.
     */
    private MethodNode splitConstructor(MethodNode before, MethodNode after) {
        int beforeCut = superCall(before);
        int afterCut = superCall(after);
        String where = baseline.name.replace('/', '.');
        if (beforeCut < 0 || afterCut < 0 || !code(before, 0, beforeCut + 1).equals(code(after, 0, afterCut + 1))) {
            throw new UnsupportedOperationException("a mixin changes a constructor of " + where + " before its super call");
        }
        AbstractInsnNode[] prefix = before.instructions.toArray();
        for (int i = 0; i <= beforeCut; i++) {
            int opcode = prefix[i].getOpcode();
            if (prefix[i] instanceof IincInsnNode || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                // The moved part would read a local variable it never got.
                throw new UnsupportedOperationException("a constructor of " + where + " keeps a local variable across its super call");
            }
        }
        MethodNode rest = move(after, "init", new InsnList(), new ArrayList<>());
        copy(after, afterCut + 1, rest, where);

        for (int i = prefix.length - 1; i > beforeCut; i--) {
            before.instructions.remove(prefix[i]);
        }
        before.tryCatchBlocks.removeIf(block -> !before.instructions.contains(block.start)
                || !before.instructions.contains(block.end) || !before.instructions.contains(block.handler));
        call(before, rest);
        return before;
    }

    /** Index of the call that initializes {@code this}, skipping the constructors of objects made for its arguments. */
    private static int superCall(MethodNode constructor) {
        int open = 0;
        AbstractInsnNode[] instructions = constructor.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i].getOpcode() == Opcodes.NEW) {
                open++;
            } else if (instructions[i] instanceof MethodInsnNode call && call.name.equals("<init>") && open-- == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The class has run its static initializer. Mixin appends to it, so what it appended becomes a method
     * for the loader to run once.
     */
    private void initialize(MethodNode before, MethodNode after) {
        int start = 0;
        if (before != null) {
            if (code(before, 0, before.instructions.size()).equals(code(after, 0, after.instructions.size()))) {
                return;
            }
            // What ran is everything before the return the baseline ends with; Mixin has to have kept it.
            int end = before.instructions.size() - 1;
            while (end > 0 && before.instructions.get(end).getOpcode() != Opcodes.RETURN) {
                end--;
            }
            int ran = 0;
            for (int i = 0; i < end; i++) {
                ran += before.instructions.get(i).getOpcode() >= 0 ? 1 : 0;
            }
            for (; start < after.instructions.size() && ran > 0; start++) {
                ran -= after.instructions.get(start).getOpcode() >= 0 ? 1 : 0;
            }
            if (!code(before, 0, end).equals(code(after, 0, start))) {
                // shortcut: what the mixins append is dropped along with what they changed; tell the two apart once a mod needs both
                skipped.add("a change to the static initializer of " + baseline.name.replace('/', '.') + ", which has already run");
                return;
            }
        }
        MethodNode appended = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "initialize", "()V", null, null);
        appended.maxStack = after.maxStack;
        appended.maxLocals = after.maxLocals;
        copy(after, start, appended, baseline.name.replace('/', '.'));
        for (AbstractInsnNode instruction : appended.instructions) {
            // Only the class's own static initializer may set its final fields, and compiled code may have their values built in.
            if (instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(baseline.name)
                    && baseline.fields.stream().anyMatch(field -> field.name.equals(put.name) && (field.access & Opcodes.ACC_FINAL) != 0)) {
                throw new UnsupportedOperationException("code a mixin appends to the static initializer of "
                        + baseline.name.replace('/', '.') + " sets its final field " + put.name);
            }
        }
        // The frames Mixin computed carry the local variables of the part that stays behind.
        appended = recompute(appended);
        appended.name = "initialize$" + Integer.toHexString(code(appended, 0, appended.instructions.size()).hashCode());
        companion.methods.add(appended);
        initializer = appended.name;
    }

    /** Copies the instructions from this index on, with the try blocks that lie within them. */
    private static void copy(MethodNode from, int start, MethodNode to, String where) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        AbstractInsnNode[] instructions = from.instructions.toArray();
        for (int i = start; i < instructions.length; i++) {
            if (instructions[i] instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        for (int i = start; i < instructions.length; i++) {
            List<LabelNode> named = switch (instructions[i]) {
                case JumpInsnNode jump -> List.of(jump.label);
                case TableSwitchInsnNode table -> Stream.concat(Stream.of(table.dflt), table.labels.stream()).toList();
                case LookupSwitchInsnNode lookup -> Stream.concat(Stream.of(lookup.dflt), lookup.labels.stream()).toList();
                case LineNumberNode line -> List.of(line.start);
                default -> List.of();
            };
            if (labels.keySet().containsAll(named)) {
                to.instructions.add(instructions[i].clone(labels));
            } else if (!(instructions[i] instanceof LineNumberNode)) {
                throw new UnsupportedOperationException("code a mixin adds to " + where + " jumps into code that has already run");
            }
        }
        for (TryCatchBlockNode block : from.tryCatchBlocks) {
            boolean isBefore = !labels.containsKey(block.start);
            if (isBefore != !labels.containsKey(block.end) || isBefore != !labels.containsKey(block.handler)) {
                throw new UnsupportedOperationException("a try block in " + where + " spans code a mixin adds and code that has already run");
            }
            if (!isBefore) {
                to.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
            }
        }
    }

    /** The method with stack map frames computed for it alone. */
    private MethodNode recompute(MethodNode method) {
        ClassNode holder = new ClassNode();
        holder.version = companion.version;
        holder.access = Opcodes.ACC_PUBLIC;
        holder.name = companion.name;
        holder.superName = "java/lang/Object";
        holder.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // The default loads both classes; class files answer the same without that.
                for (String ancestor = first; ancestor != null; ancestor = superName(ancestor)) {
                    if (classes.isSubclass(second, ancestor)) {
                        return ancestor;
                    }
                }
                return "java/lang/Object";
            }

            private String superName(String name) {
                ClassNode node = classes.get(name);
                return node == null || (node.access & Opcodes.ACC_INTERFACE) != 0 ? null : node.superName;
            }
        };
        holder.accept(writer);
        ClassNode computed = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(computed, ClassReader.EXPAND_FRAMES);
        return computed.methods.getFirst();
    }

    /**
     * The instructions from {@code start} to {@code end} with labels as positions and without frames, so
     * that code Mixin wrote out again unchanged compares equal.
     */
    private static List<Object> code(MethodNode method, int start, int end) {
        Map<LabelNode, Integer> positions = new HashMap<>();
        int position = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) {
                positions.put(label, position);
            } else if (instruction.getOpcode() >= 0) {
                position++;
            }
        }
        List<Object> code = new ArrayList<>();
        for (int i = start; i < end; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction.getOpcode() < 0) {
                continue;
            }
            code.add(instruction.getOpcode());
            switch (instruction) {
                case VarInsnNode variable -> code.add(variable.var);
                case IntInsnNode constant -> code.add(constant.operand);
                case TypeInsnNode type -> code.add(type.desc);
                case FieldInsnNode field -> code.add(field.owner + '.' + field.name + ':' + field.desc);
                case MethodInsnNode call -> code.add(call.owner + '.' + call.name + call.desc);
                case InvokeDynamicInsnNode dynamic -> code.add(List.of(dynamic.name, dynamic.desc, dynamic.bsm, List.of(dynamic.bsmArgs)));
                case JumpInsnNode jump -> code.add(positions.get(jump.label));
                case LdcInsnNode constant -> code.add(constant.cst);
                case IincInsnNode increment -> code.add(List.of(increment.var, increment.incr));
                case TableSwitchInsnNode table -> code.add(List.of(table.min, positions.get(table.dflt), table.labels.stream().map(positions::get).toList()));
                case LookupSwitchInsnNode lookup -> code.add(List.of(lookup.keys, positions.get(lookup.dflt), lookup.labels.stream().map(positions::get).toList()));
                case MultiANewArrayInsnNode array -> code.add(List.of(array.desc, array.dims));
                default -> {
                }
            }
        }
        if (start == 0 && end == method.instructions.size()) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                code.add(List.of(positions.get(block.start), positions.get(block.end), positions.get(block.handler), String.valueOf(block.type)));
            }
        }
        return code;
    }
}
