package net.ccbluex.modloader.mixin.compiler;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Hands the instructions a loaded game class would refuse to {@code Linker}: access to a member that is not
 * public, and use of a field, method or interface that exists only because a mixin says so. Each becomes an
 * invokedynamic with the same effect on the stack, so the frames of the method stay valid.
 *
 * <p>A companion is in a package and class loader of its own, so it cannot name a class that is not public,
 * though the code it got from the mixed class does. Such a type becomes its nearest public superclass
 * throughout the companion, and whatever needs the class itself goes through {@code Linker}.
 *
 * @agent claude-fable-5-2
 * @agent claude-opus-5-5
 */
final class Rewriter {

    /**
     * The class the methods of a mixed class moved to.
     *
     * @param target internal name of the mixed class
     * @param name internal name of the companion
     * @param methods name and descriptor of every method the mixins added to the target, to the companion's
     */
    record Companion(String target, String name, Map<String, MethodNode> methods, Set<String> overridden) {
    }

    static final Handle LINK = new Handle(Opcodes.H_INVOKESTATIC, "net/ccbluex/modloader/bridge/Bridge", "link",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);

    private final ClassFiles game;
    private final Set<String> interfaces;

    /**
     * @param game the classes of the game, without the mods
     * @param interfaces internal names of the interfaces mixins add to loaded classes
     */
    Rewriter(ClassFiles game, Set<String> interfaces) {
        this.game = game;
        this.interfaces = interfaces;
    }

    /**
     * @param hierarchy class files to look the superclasses of {@code node} up in
     * @param companion what {@code node} is the companion of, null for a class of a mod
     * @return whether anything changed
     */
    boolean rewrite(ClassNode node, ClassFiles hierarchy, Companion companion) {
        // A companion sits in a package of its own; a mod's class reaches its own package but no hidden game class.
        ClassFiles hidden = companion != null ? hierarchy : game;
        boolean changed = false;
        for (MethodNode method : List.copyOf(node.methods)) {
            changed |= constructors(method, hierarchy, companion != null);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                AbstractInsnNode replacement = switch (instruction) {
                    case FieldInsnNode field -> field(node, hierarchy, hidden, companion, field);
                    case MethodInsnNode call -> call(node, hierarchy, hidden, companion, call);
                    case TypeInsnNode type -> type(hidden, type);
                    default -> null;
                };
                if (replacement != null) {
                    method.instructions.set(instruction, replacement);
                    changed = true;
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    changed |= handles(node, hierarchy, hidden, companion, dynamic);
                }
            }
        }
        return erase(node, hidden) || changed;
    }

    /**
     * Code the companion got from the mixed class, and a mod compiled against an access widener, may construct what
     * they may not: a hidden class, or a game class by a constructor that is not public. {@code new C; dup;
     * arguments; invokespecial C.<init>} then becomes the arguments and one call into Linker, and the frames in
     * between lose the two uninitialized values.
     */
    private boolean constructors(MethodNode method, ClassFiles hierarchy, boolean isCompanion) {
        boolean changed = false;
        Deque<TypeInsnNode> created = new ArrayDeque<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.NEW) {
                created.push((TypeInsnNode) instruction);
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call && call.name.equals("<init>")) || created.isEmpty()
                    || !created.peek().desc.equals(call.owner)) {
                continue;
            }
            TypeInsnNode creation = created.pop();
            if (!(isCompanion || isGame(call.owner)) || isConstructible(call.owner, call.desc, hierarchy)
                    || creation.getNext().getOpcode() != Opcodes.DUP) {
                continue;
            }
            // Frames name the uninitialized value by the label of its new instruction.
            Set<LabelNode> labels = new HashSet<>();
            for (AbstractInsnNode before = creation.getPrevious(); before != null && before.getOpcode() < 0; before = before.getPrevious()) {
                if (before instanceof LabelNode label) {
                    labels.add(label);
                }
            }
            for (AbstractInsnNode between = creation; between != call; between = between.getNext()) {
                if (between instanceof FrameNode frame && frame.stack != null) {
                    frame.stack = frame.stack.stream().filter(type -> !labels.contains(type)).toList();
                }
            }
            method.instructions.remove(creation.getNext());
            method.instructions.remove(creation);
            method.instructions.set(call, link("new", Type.getMethodDescriptor(Type.getObjectType(call.owner), Type.getArgumentTypes(call.desc)),
                    "new", call.owner.replace('/', '.'), call.desc));
            changed = true;
        }
        return changed;
    }

    private static boolean isConstructible(String owner, String descriptor, ClassFiles hierarchy) {
        ClassNode node = hierarchy.get(owner);
        if (node == null) {
            return true;
        }
        if ((node.access & Opcodes.ACC_PUBLIC) == 0) {
            return false;
        }
        for (MethodNode constructor : node.methods) {
            if (constructor.name.equals("<init>") && constructor.desc.equals(descriptor)) {
                return (constructor.access & Opcodes.ACC_PUBLIC) != 0;
            }
        }
        return true;
    }

    private AbstractInsnNode field(ClassNode from, ClassFiles hierarchy, ClassFiles hidden, Companion companion, FieldInsnNode field) {
        // A field of a mod is erased where it is declared too.
        if (!(isHidden(field.desc, hidden) && (companion != null || isGame(field.owner)))) {
            if (!isGame(field.owner)) {
                return null;
            }
            boolean isWrite = field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC;
            ClassFiles.Member member = game.field(field.owner, field.name, field.desc);
            // Only the class itself may write its final fields, and only while it initializes.
            if (member != null && isAccessible(field.owner, member, from, hierarchy) && !(isWrite && (member.access() & Opcodes.ACC_FINAL) != 0)) {
                return null;
            }
        }
        String owner = field.owner.replace('/', '.');
        return switch (field.getOpcode()) {
            case Opcodes.GETFIELD -> link(field.name, "(Ljava/lang/Object;)" + field.desc, "getfield", owner);
            case Opcodes.PUTFIELD -> link(field.name, "(Ljava/lang/Object;" + field.desc + ")V", "putfield", owner);
            case Opcodes.GETSTATIC -> link(field.name, "()" + field.desc, "getstatic", owner);
            default -> link(field.name, "(" + field.desc + ")V", "putstatic", owner);
        };
    }

    private AbstractInsnNode call(ClassNode from, ClassFiles hierarchy, ClassFiles hidden, Companion companion, MethodInsnNode call) {
        if (call.name.equals("<init>") || call.owner.startsWith("[")) {
            // shortcut: a constructor the mod may not call fails as it would without us; construct through Linker once a mod needs it
            return null;
        }
        String owner = call.owner.replace('/', '.');
        if (companion != null && call.owner.equals(companion.target())) {
            MethodNode moved = companion.methods().get(call.name + call.desc);
            boolean isOverridden = call.getOpcode() != Opcodes.INVOKESTATIC
                    && call.getOpcode() != Opcodes.INVOKESPECIAL
                    && companion.overridden().contains(call.name + call.desc);
            if (moved != null && !isOverridden) {
                return new MethodInsnNode(Opcodes.INVOKESTATIC, companion.name(), moved.name, moved.desc, false);
            }
        }
        if (companion != null && call.owner.equals(companion.name())) {
            return null;
        }
        // The descriptor erases to other types here; Linker finds the method by the one it was declared with, which
        // for a method of a mod is erased of hidden game classes, as every class of a mod is.
        String declared = !isHidden(call.desc, hidden) ? null : isGame(call.owner) ? call.desc : erase(call.desc, game);
        if (declared != null && companion == null && !isGame(call.owner)) {
            return null;
        }
        if (call.getOpcode() == Opcodes.INVOKESPECIAL) {
            // In a companion this is a super call or a private call the target's code made; a mod's own stay.
            return companion == null ? null
                    : link(call.name, withReceiver(call.desc), "invokespecial", owner, companion.target().replace('/', '.'), declared);
        }
        if (declared == null) {
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE && interfaces.contains(call.owner)) {
                return link(call.name, withReceiver(call.desc), "invokeinterface", owner);
            }
            if (!isGame(call.owner)) {
                return null;
            }
            ClassFiles.Member member = game.method(call.owner, call.name, call.desc);
            if (member != null && isAccessible(call.owner, member, from, hierarchy)) {
                return null;
            }
        }
        return switch (call.getOpcode()) {
            case Opcodes.INVOKESTATIC -> link(call.name, call.desc, "invokestatic", owner, declared);
            case Opcodes.INVOKEINTERFACE -> link(call.name, withReceiver(call.desc), "invokeinterface", owner, declared);
            default -> link(call.name, withReceiver(call.desc), "invokevirtual", owner, declared);
        };
    }

    private AbstractInsnNode type(ClassFiles hidden, TypeInsnNode type) {
        String descriptor = Type.getObjectType(type.desc).getDescriptor();
        boolean isHidden = isHidden(descriptor, hidden);
        if (!interfaces.contains(type.desc) && !isHidden) {
            return null;
        }
        String name = type.desc.replace('/', '.');
        return switch (type.getOpcode()) {
            // A hidden class is in the frames as its public superclass, which the erased result type then is.
            case Opcodes.CHECKCAST -> link("cast", "(Ljava/lang/Object;)" + (isHidden ? descriptor : "Ljava/lang/Object;"), "checkcast", name);
            case Opcodes.INSTANCEOF -> link("is", "(Ljava/lang/Object;)Z", "instanceof", name);
            default -> null;
        };
    }

    /** A lambda or method reference names its method by handle; one the game refuses gets a method to go through. */
    private boolean handles(ClassNode node, ClassFiles hierarchy, ClassFiles hidden, Companion companion, InvokeDynamicInsnNode dynamic) {
        boolean changed = false;
        for (int i = 0; i < dynamic.bsmArgs.length; i++) {
            if (!(dynamic.bsmArgs[i] instanceof Handle handle)) {
                continue;
            }
            int opcode = switch (handle.getTag()) {
                case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                default -> -1;
            };
            if (opcode < 0) {
                continue;
            }
            AbstractInsnNode replacement = call(node, hierarchy, hidden, companion,
                    new MethodInsnNode(opcode, handle.getOwner(), handle.getName(), handle.getDesc(), handle.isInterface()));
            if (replacement instanceof MethodInsnNode moved) {
                dynamic.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, moved.owner, moved.name, moved.desc, false);
                changed = true;
            } else if (replacement != null) {
                Type[] captured = Type.getArgumentTypes(dynamic.desc);
                boolean isLambda = dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory");
                String receiver = isLambda && captured.length > 0 ? captured[0].getDescriptor()
                        : "L" + handle.getOwner() + ";";
                String descriptor = opcode == Opcodes.INVOKESTATIC ? handle.getDesc()
                        : "(" + receiver + handle.getDesc().substring(1);
                MethodNode stub = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        "modloader$" + node.methods.size(), descriptor, null, null);
                int local = 0;
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    stub.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                    local += argument.getSize();
                }
                stub.instructions.add(replacement);
                stub.instructions.add(new InsnNode(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)));
                stub.maxLocals = local;
                stub.maxStack = Math.max(local, 2);
                node.methods.add(stub);
                dynamic.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, node.name, stub.name, descriptor,
                        (node.access & Opcodes.ACC_INTERFACE) != 0);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Puts the nearest public superclass in place of every class the class cannot name, wherever the JVM looks a
     * type up or checks it: its fields and methods, the remaining member references, invokedynamic and the frames.
     * Every reference whose member is declared with such a type already goes through Linker.
     *
     * @return whether anything changed
     */
    private static boolean erase(ClassNode node, ClassFiles hierarchy) {
        boolean changed = false;
        for (FieldNode field : node.fields) {
            String erased = erase(field.desc, hierarchy);
            if (!erased.equals(field.desc)) {
                field.desc = erased;
                field.signature = null;
                changed = true;
            }
        }
        for (MethodNode method : node.methods) {
            String erasedMethod = erase(method.desc, hierarchy);
            if (erasedMethod.equals(method.desc) && !mentionsHidden(method, hierarchy)) {
                continue;
            }
            changed = true;
            method.desc = erasedMethod;
            method.signature = null;
            if (method.localVariables != null) {
                for (LocalVariableNode variable : method.localVariables) {
                    variable.desc = erase(variable.desc, hierarchy);
                    variable.signature = null;
                }
            }
            for (AbstractInsnNode instruction : method.instructions) {
                switch (instruction) {
                    case MethodInsnNode call -> call.desc = erase(call.desc, hierarchy);
                    case FieldInsnNode field -> field.desc = erase(field.desc, hierarchy);
                    case InvokeDynamicInsnNode dynamic -> {
                        Type functional = Type.getReturnType(dynamic.desc);
                        if (dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                                && isHidden(functional.getDescriptor(), hierarchy)) {
                            dynamic.bsmArgs = new Object[] {"lambda", functional.getClassName(), dynamic.bsmArgs[1]};
                            dynamic.bsm = LINK;
                        }
                        dynamic.desc = erase(dynamic.desc, hierarchy);
                        dynamic.bsmArgs = Arrays.stream(dynamic.bsmArgs).map(argument -> erase(argument, hierarchy)).toArray();
                    }
                    case LdcInsnNode constant -> constant.cst = erase(constant.cst, hierarchy);
                    case FrameNode frame -> {
                        frame.local = frame.local == null ? null : frame.local.stream().map(type -> eraseFrame(type, hierarchy)).toList();
                        frame.stack = frame.stack == null ? null : frame.stack.stream().map(type -> eraseFrame(type, hierarchy)).toList();
                    }
                    default -> {
                    }
                }
            }
        }
        return changed;
    }

    /** Whether code or frames of the method name a class the class cannot name. */
    private static boolean mentionsHidden(MethodNode method, ClassFiles hierarchy) {
        for (AbstractInsnNode instruction : method.instructions) {
            boolean isHidden = switch (instruction) {
                case MethodInsnNode call -> isHidden(call.desc, hierarchy);
                case FieldInsnNode field -> isHidden(field.desc, hierarchy);
                case InvokeDynamicInsnNode dynamic -> isHidden(dynamic.desc, hierarchy)
                        || Arrays.stream(dynamic.bsmArgs).anyMatch(argument -> !erase(argument, hierarchy).equals(argument));
                case LdcInsnNode constant -> !erase(constant.cst, hierarchy).equals(constant.cst);
                case FrameNode frame -> Stream.concat(frame.local == null ? Stream.empty() : frame.local.stream(),
                        frame.stack == null ? Stream.empty() : frame.stack.stream())
                        .anyMatch(type -> !Objects.equals(eraseFrame(type, hierarchy), type));
                default -> false;
            };
            if (isHidden) {
                return true;
            }
        }
        return false;
    }

    private static Object erase(Object constant, ClassFiles hierarchy) {
        return switch (constant) {
            case Type type when type.getSort() == Type.METHOD -> Type.getMethodType(erase(type.getDescriptor(), hierarchy));
            case Handle handle -> new Handle(handle.getTag(), handle.getOwner(), handle.getName(),
                    erase(handle.getDesc(), hierarchy), handle.isInterface());
            default -> constant;
        };
    }

    /** A frame names a class by internal name and an array by descriptor. */
    private static Object eraseFrame(Object type, ClassFiles hierarchy) {
        if (!(type instanceof String name)) {
            return type;
        }
        return name.startsWith("[") ? erase(name, hierarchy) : Type.getType(erase("L" + name + ";", hierarchy)).getInternalName();
    }

    /** A field or method descriptor with every hidden class replaced. */
    private static String erase(String descriptor, ClassFiles hierarchy) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = Type.getType(erase(arguments[i].getDescriptor(), hierarchy));
            }
            return Type.getMethodDescriptor(Type.getType(erase(Type.getReturnType(descriptor).getDescriptor(), hierarchy)), arguments);
        }
        if (type.getSort() == Type.ARRAY) {
            return "[".repeat(type.getDimensions()) + erase(type.getElementType().getDescriptor(), hierarchy);
        }
        if (type.getSort() != Type.OBJECT) {
            return descriptor;
        }
        String name = type.getInternalName();
        for (var node = hierarchy.get(name); node != null && (node.access & Opcodes.ACC_PUBLIC) == 0; node = hierarchy.get(name)) {
            name = node.superName == null ? "java/lang/Object" : node.superName;
        }
        return "L" + name + ";";
    }

    /** Whether the descriptor names a class the companion cannot. */
    private static boolean isHidden(String descriptor, ClassFiles hierarchy) {
        return !erase(descriptor, hierarchy).equals(descriptor);
    }

    /** A class of the game: not of the JDK, which nothing here changes, and not of a mod, whose code links as compiled. */
    private boolean isGame(String owner) {
        return !owner.startsWith("[") && !owner.startsWith("java/") && !owner.startsWith("javax/")
                && !owner.startsWith("jdk/") && !owner.startsWith("sun/")
                && !owner.startsWith("org/spongepowered/asm/synthetic/") && game.get(owner) != null;
    }

    /** A mod never shares a package with a game class at runtime, so only public and inherited protected members link. */
    private boolean isAccessible(String owner, ClassFiles.Member member, ClassNode from, ClassFiles hierarchy) {
        if ((game.get(owner).access & Opcodes.ACC_PUBLIC) == 0) {
            return false;
        }
        if ((member.access() & Opcodes.ACC_PUBLIC) != 0) {
            return true;
        }
        return (member.access() & Opcodes.ACC_PROTECTED) != 0 && from.superName != null
                && hierarchy.isSubclass(from.superName, member.declaring().name);
    }

    private static String withReceiver(String descriptor) {
        return "(Ljava/lang/Object;" + descriptor.substring(1);
    }

    /** Arguments after the kind and the owner that are null are left out. */
    private static InvokeDynamicInsnNode link(String name, String descriptor, String kind, String owner, String... more) {
        Object[] arguments = Stream.concat(Stream.of(kind, owner), Stream.of(more).filter(Objects::nonNull)).toArray();
        return new InvokeDynamicInsnNode(name, descriptor, LINK, arguments);
    }
}
