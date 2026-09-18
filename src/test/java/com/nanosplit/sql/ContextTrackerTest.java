package com.nanosplit.sql;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextTrackerTest {

    private static final Pattern CONTEXT = Pattern.compile(
            "^(USE\\s|SET\\s+(IDENTITY_INSERT|ANSI_NULLS)\\b)", Pattern.CASE_INSENSITIVE);

    @Test
    public void tracksUseAndReplaysIt() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        assertTrue(tracker.observe("USE [MyDb]"));
        assertEquals(1, tracker.replay().size());
        assertEquals("USE [MyDb]", tracker.replay().get(0));
    }

    @Test
    public void laterUseReplacesEarlierOne() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        tracker.observe("USE [A]");
        tracker.observe("USE [B]");
        assertEquals(1, tracker.replay().size());
        assertEquals("USE [B]", tracker.replay().get(0));
    }

    @Test
    public void identityInsertOnIsRememberedAndClosed() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        tracker.observe("SET IDENTITY_INSERT [dbo].[Foo] ON");
        assertEquals(1, tracker.replay().size());
        List<String> closers = tracker.closers();
        assertEquals(1, closers.size());
        assertEquals("SET IDENTITY_INSERT [dbo].[Foo] OFF", closers.get(0));
    }

    @Test
    public void identityInsertOffClearsIt() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        tracker.observe("SET IDENTITY_INSERT [dbo].[Foo] ON");
        tracker.observe("SET IDENTITY_INSERT [dbo].[Foo] OFF");
        assertTrue(tracker.replay().isEmpty());
        assertTrue(tracker.closers().isEmpty());
    }

    @Test
    public void twoDifferentTablesBothTrackedIndependently() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        tracker.observe("SET IDENTITY_INSERT [dbo].[Foo] ON");
        tracker.observe("SET IDENTITY_INSERT [dbo].[Bar] ON");
        tracker.observe("SET IDENTITY_INSERT [dbo].[Foo] OFF");
        assertEquals(1, tracker.replay().size());
        assertEquals(1, tracker.closers().size());
        assertEquals("SET IDENTITY_INSERT [dbo].[Bar] OFF", tracker.closers().get(0));
    }

    @Test
    public void nonMatchingStatementIsIgnored() {
        ContextTracker tracker = new ContextTracker(CONTEXT);
        assertFalse(tracker.observe("INSERT [dbo].[Foo] VALUES (1)"));
        assertTrue(tracker.replay().isEmpty());
    }
}
