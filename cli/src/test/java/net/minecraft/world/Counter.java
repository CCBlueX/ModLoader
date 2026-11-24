package net.minecraft.world;

/**
 * What the mixins of the test mod target: private state, a private method, a constructor and a static.
 *
 * @agent claude-fable-5-2
 */
public class Counter {

    protected static int created;

    private final String name;
    private int count;

    public Counter(String name) {
        this.name = name;
        created++;
    }

    public int tick() {
        count += step();
        return count;
    }

    private int step() {
        return 1;
    }

    public String describe() {
        return name + ":" + count;
    }
}
