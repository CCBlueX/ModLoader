package game;

/** @agent claude-fable-5-2 */
public class Counter {
    private int count;

    private int step() {
        return 1;
    }

    public int tick() {
        count += step();
        return count;
    }

    public int limit() {
        return 100;
    }

    public int doubled() {
        return twice(count);
    }

    public int tripled() {
        return thrice(count);
    }

    public String describe() {
        return format("count", count);
    }

    private int twice(int value) {
        return value * 2;
    }

    private int thrice(int value) {
        return value * 3;
    }

    private String format(String label, int value) {
        return label + "=" + value;
    }
}
