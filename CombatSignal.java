package com.playerlogger;

/** Structured moderation signal emitted by CombatManager. */
public record CombatSignal(
        Rule rule,
        Kind kind,
        Severity severity,
        double confidence,
        String detail
) {
    public enum Kind { ALERT, CONTEXT, MITIGATING }
    public enum Severity { HIGH, MEDIUM, LOW }
    public enum Rule {
        SPAWN_ATTACK,
        COMBAT_LOGOUT,
        REPEATED_KILL,
        DEATH_LOOT,
        DEATH_LOOT_RETURNED,
        UNPROVOKED_ATTACK,
        ENVIRONMENTAL_ATTACK,
        ATTACK_AFTER_LOGIN,
        ATTACK_AFTER_TELEPORT,
        REPEAT_ENCOUNTER,
        RETURNED_TO_FIGHT,
        LEFT_RESPAWN,
        SLEEPING_VICTIM,
        INVENTORY_OPEN,
        INACTIVE_VICTIM,
        RECENT_AGGRESSION_CONTEXT,
        KICK_DISCONNECT
    }
}
