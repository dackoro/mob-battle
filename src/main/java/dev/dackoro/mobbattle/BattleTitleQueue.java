package dev.dackoro.mobbattle;

import java.util.ArrayDeque;

/** Keep each victory, coalesce repeated start previews, and display victories first. */
final class BattleTitleQueue<T> {
    private final ArrayDeque<T> victories = new ArrayDeque<>();
    private T latestStart;

    void offer(T value, boolean victory) {
        if (victory) {
            victories.addLast(value);
            latestStart = null;
        }
        else latestStart = value;
    }

    T poll() {
        if (!victories.isEmpty()) return victories.removeFirst();
        T result = latestStart;
        latestStart = null;
        return result;
    }

    boolean isEmpty() { return victories.isEmpty() && latestStart == null; }
}
