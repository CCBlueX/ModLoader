package net.minecraft.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.function.Supplier;
import net.minecraft.world.Bookshelf;
import net.minecraft.world.Counter;
import net.minecraft.world.Label;
import net.minecraft.world.Vault;

/**
 * The game of the tests: the agent takes the class loader of the class with this name for the game's. Runs
 * the commands it reads, one per line, and prints what they return.
 *
 * @agent claude-fable-5-2
 */
public final class Main {

    public static Counter counter = new Counter("first");

    public static Vault vault = new Vault();

    public static Label label = new Label(1, "first");

    private static final MinecraftServer SERVER = new MinecraftServer();

    /** For a loaded mod to answer the {@code hook} command. */
    public static Supplier<String> hook;

    private Main() {
    }

    public static void main(String[] arguments) throws IOException {
        System.out.println("started");
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
        for (String command = commands.readLine(); command != null; command = commands.readLine()) {
            try {
                System.out.println(switch (command) {
                    case "tick" -> counter.tick();
                    case "describe" -> counter.describe();
                    case "new" -> (counter = new Counter("second")).describe();
                    case "label" -> (label = new Label(2, "second")).text();
                    case "shelve" -> new Bookshelf().put("book");
                    case "hook" -> hook == null ? "no hook" : hook.get();
                    case "server" -> {
                        SERVER.tickServer(() -> true);
                        yield "ticked";
                    }
                    default -> "unknown command";
                });
            } catch (RuntimeException | LinkageError e) {
                System.out.println(e);
            }
        }
    }
}
