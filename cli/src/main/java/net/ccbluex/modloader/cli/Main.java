package net.ccbluex.modloader.cli;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** @agent claude-fable-5-2 */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar modloader.jar <command> [--pid <pid>]
              load <mod.jar>...   load mods into the running game
              unload <id>...      unload mods loaded this way
              list                show the mods loaded this way
            Without --pid the one running Minecraft is used.""";

    /** Main classes and jar names of vanilla, Fabric, NeoForge and Forge launches. */
    private static final Pattern MINECRAFT = Pattern.compile("minecraft|fabric|forge", Pattern.CASE_INSENSITIVE);

    private Main() {
    }

    public static void main(String[] arguments) throws Exception {
        List<String> request = new ArrayList<>(List.of(arguments));
        String pid = null;
        int flag = request.indexOf("--pid");
        if (flag >= 0 && flag + 1 < request.size()) {
            pid = request.get(flag + 1);
            request.subList(flag, flag + 2).clear();
        }
        String command = request.isEmpty() ? "" : request.getFirst();
        if (!List.of("load", "unload", "list").contains(command) || (request.size() == 1) != command.equals("list")) {
            System.err.println(USAGE);
            System.exit(2);
        }
        if (command.equals("load")) {
            request.replaceAll(item -> item.equals("load") ? item : Path.of(item).toAbsolutePath().toString());
            for (String jar : request.subList(1, request.size())) {
                if (!Files.isRegularFile(Path.of(jar))) {
                    fail("no such file: " + jar);
                }
            }
        }
        try {
            String answer = send(pid != null ? pid : game(), String.join("\n", request));
            System.out.println(answer);
            System.exit(answer.lines().anyMatch(line -> line.startsWith("error: ")) ? 1 : 0);
        } catch (NoClassDefFoundError e) {
            fail("this Java has no attach support; run the CLI with a JDK (module jdk.attach)");
        }
    }

    private static String game() {
        long self = ProcessHandle.current().pid();
        List<VirtualMachineDescriptor> running = VirtualMachine.list().stream()
                .filter(vm -> !vm.id().equals(String.valueOf(self)))
                .toList();
        List<VirtualMachineDescriptor> games = running.stream()
                .filter(vm -> MINECRAFT.matcher(vm.displayName()).find())
                .toList();
        if (games.size() == 1) {
            return games.getFirst().id();
        }
        StringBuilder message = new StringBuilder(games.isEmpty() ? "no running Minecraft found" : "several games are running")
                .append("; pass --pid with one of:");
        for (VirtualMachineDescriptor vm : games.isEmpty() ? running : games) {
            message.append("\n  ").append(vm.id()).append("  ").append(vm.displayName());
        }
        fail(message.toString());
        return null;
    }

    private static String send(String pid, String request) throws Exception {
        Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path answer = Files.createTempFile("modloader-", ".txt");
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                vm.loadAgent(jar.toString(), answer + "\n" + jar + "\n" + request);
            } finally {
                vm.detach();
            }
            return Files.readString(answer);
        } finally {
            Files.delete(answer);
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(1);
    }
}
