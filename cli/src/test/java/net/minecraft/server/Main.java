package net.minecraft.server;

/**
 * The game of LoadTest: the agent takes the class loader of the class with this name for the game's.
 *
 * @agent claude-fable-5-2
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] arguments) throws InterruptedException {
        System.out.println("started");
        Thread.sleep(Long.MAX_VALUE);
    }
}
