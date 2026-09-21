package com.playerlogger;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ActivityEvidenceTest {
    // Exercise the real grouping accumulator without starting Bukkit or a Minecraft server.
    private static final Class<?> GROUP;
    private static final Constructor<?> CONSTRUCTOR;
    private static final Method ADD;
    private static final Method ITEM;
    private static final Field EVIDENCE;
    static {
        try {
            GROUP = Class.forName("com.playerlogger.ActivityTracker$FeedAccumulator");
            CONSTRUCTOR = GROUP.getDeclaredConstructor(long.class, String.class, String.class,
                    ActivityTracker.ActivityEvent.class);
            ADD = GROUP.getDeclaredMethod("add", ActivityTracker.ActivityEvent.class);
            ITEM = GROUP.getDeclaredMethod("toRecord");
            EVIDENCE = GROUP.getDeclaredField("evidence");
            CONSTRUCTOR.setAccessible(true);
            ADD.setAccessible(true);
            ITEM.setAccessible(true);
            EVIDENCE.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private ActivityTracker.ActivityEvent event(String world, int chunkX, int chunkZ, LogRecord evidence) {
        return new ActivityTracker.ActivityEvent(1_790_000_000_123L, ActivityTracker.Kind.LOGOUT,
                "", world, chunkX * 16, 64, chunkZ * 16, chunkX, chunkZ, evidence);
    }

    private Object group(long bucket, String player, String category, ActivityTracker.ActivityEvent event) throws Exception {
        Object group = CONSTRUCTOR.newInstance(bucket, player, category, event);
        ADD.invoke(group, event);
        return group;
    }

    private ActivityTracker.ActivityFeedItem item(Object group) throws Exception {
        return (ActivityTracker.ActivityFeedItem) ITEM.invoke(group);
    }

    @Test void logoutLinksToItsExactRecord() throws Exception {
        LogRecord log = new LogRecord("2026-09-20 21:30:12", "LOGOUT at world (1,64,2)");
        Object group = group(123, "BirdmanAustin", "Logout", event("world", 0, 0, log));
        assertEquals("BirdmanAustin left the server", item(group).title());
        assertEquals("activity", item(group).evidenceType());
        assertEquals(item(group).id(), item(group).evidenceId());
        assertNotEquals("BirdmanAustin", item(group).evidenceId());
        assertEquals(List.of(log), EVIDENCE.get(group));
    }

    @Test void sameTimeEventsInDifferentPlacesHaveDistinctLinks() throws Exception {
        var base = item(group(123, "BirdmanAustin", "Logout", event("world", 0, 0, null)));
        assertNotEquals(base.id(), item(group(123, "BirdmanAustin", "Logout", event("nether", 0, 0, null))).id());
        assertNotEquals(base.id(), item(group(123, "BirdmanAustin", "Logout", event("world", 1, 0, null))).id());
        assertNotEquals(base.id(), item(group(123, "BirdmanAustin", "Logout", event("world", 0, -1, null))).id());
        assertNotEquals(base.id(), item(group(124, "BirdmanAustin", "Logout", event("world", 0, 0, null))).id());
        assertNotEquals(base.id(), item(group(123, "OtherPlayer", "Logout", event("world", 0, 0, null))).id());
        assertNotEquals(base.id(), item(group(123, "BirdmanAustin", "Login", event("world", 0, 0, null))).id());
    }

    @Test void repeatedEventsPreserveEveryRecordAndKeepTheGroupLinkStable() throws Exception {
        LogRecord first = new LogRecord("2026-09-20 21:30:12", "LOGOUT at world");
        LogRecord second = new LogRecord("2026-09-20 21:30:50", "LOGOUT at world");
        Object group = group(123, "BirdmanAustin", "Logout", event("world", 0, 0, first));
        String id = item(group).id();
        ADD.invoke(group, event("world", 0, 0, second));
        assertEquals(List.of(first, second), EVIDENCE.get(group));
        assertEquals(id, item(group).id());
        assertTrue(item(group).detail().startsWith("2 grouped events"));
    }

    @Test void missingRawLogIsExplicitlyAnObservation() throws Exception {
        Object group = group(123, "BirdmanAustin", "Logout", event("world", -2, 3, null));
        var record = (LogRecord) ((List<?>) EVIDENCE.get(group)).getFirst();
        assertTrue(record.action().startsWith("Activity observation: LOGOUT"));
        assertTrue(record.action().contains("world (-32, 64, 48)"));
        assertTrue(record.action().contains("no raw log entry attached"));
    }
}
