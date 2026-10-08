package dev.dackoro.mobbattle;

/** A last surviving side wins even when the same check also reaches the safety deadline. */
enum BattleOutcome {
    ONGOING, WIN, EMPTY, TIMEOUT;

    static BattleOutcome resolve(int livingSides, boolean expired) {
        if (livingSides == 0) return EMPTY;
        if (livingSides == 1) return WIN;
        return expired ? TIMEOUT : ONGOING;
    }
}
