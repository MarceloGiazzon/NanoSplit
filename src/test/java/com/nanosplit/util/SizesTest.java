package com.nanosplit.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SizesTest {

    @Test
    public void parsesPlainBytes() {
        assertEquals(1024L, Sizes.parse("k", "1024"));
    }

    @Test
    public void parsesKilobytes() {
        assertEquals(512L * 1024, Sizes.parse("k", "512kb"));
        assertEquals(512L * 1024, Sizes.parse("k", "512KB"));
    }

    @Test
    public void parsesMegabytesWithoutTrailingB() {
        assertEquals(64L * 1024 * 1024, Sizes.parse("k", "64M"));
    }

    @Test
    public void zeroAndBlankMeanUnlimited() {
        assertEquals(0L, Sizes.parse("k", "0"));
        assertEquals(0L, Sizes.parse("k", ""));
        assertEquals(0L, Sizes.parse("k", null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsGarbage() {
        Sizes.parse("k", "not-a-size");
    }

    @Test
    public void formatsRoundNumbers() {
        assertEquals("1.0 KB", Sizes.format(1024));
        assertEquals("1.0 MB", Sizes.format(1024 * 1024));
    }

    @Test
    public void formatsSecondsUnderAndOverAMinute() {
        assertEquals("42.3s", Sizes.formatSeconds(42.3));
        assertEquals("1m 05s", Sizes.formatSeconds(65));
        assertEquals("1h 00m 01s", Sizes.formatSeconds(3601));
    }
}
