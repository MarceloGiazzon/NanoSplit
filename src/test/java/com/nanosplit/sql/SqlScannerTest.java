package com.nanosplit.sql;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SqlScannerTest {

    private static final Pattern SPLITTABLE = Pattern.compile("^(INSERT|UPDATE|DELETE|MERGE)\\b", Pattern.CASE_INSENSITIVE);

    private static List<SqlUnit> scan(String sql) {
        SqlScanner scanner = new SqlScanner(new StringReader(sql), SPLITTABLE, "GO");
        List<SqlUnit> out = new ArrayList<SqlUnit>();
        for (SqlUnit u : scanner) {
            out.add(u);
        }
        return out;
    }

    @Test
    public void splitsConsecutiveInsertsIntoSeparateUnits() {
        String sql = "INSERT [dbo].[T] VALUES (1)\nINSERT [dbo].[T] VALUES (2)\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(3, units.size());
        assertEquals(SqlUnit.Kind.SQL, units.get(0).kind);
        assertTrue(units.get(0).splittable);
        assertEquals("INSERT [dbo].[T] VALUES (1)", units.get(0).text);
        assertEquals(SqlUnit.Kind.SQL, units.get(1).kind);
        assertEquals(SqlUnit.Kind.BATCH_SEPARATOR, units.get(2).kind);
    }

    @Test
    public void keepsMultiLineDdlAsOneUnit() {
        String sql = "CREATE TABLE [dbo].[T](\n\t[Id] [int] NOT NULL\n)\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(2, units.size());
        assertEquals(SqlUnit.Kind.SQL, units.get(0).kind);
        assertTrue(units.get(0).text.startsWith("CREATE TABLE"));
        assertTrue(units.get(0).text.contains("[Id] [int] NOT NULL"));
        assertEquals(3, units.get(0).lineCount);
    }

    @Test
    public void preservesEmbeddedNewlineInsideQuotedString() {
        String sql = "INSERT [dbo].[T] VALUES (1, N'line one\nline two')\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(2, units.size());
        assertTrue(units.get(0).splittable);
        assertTrue(units.get(0).text.contains("line one\nline two"));
    }

    @Test
    public void doesNotSplitInsideIfBeginEndBlock() {
        String sql = "IF NOT EXISTS (SELECT 1 FROM [dbo].[T])\nBEGIN\n"
                + "INSERT [dbo].[T] VALUES (1)\nINSERT [dbo].[T] VALUES (2)\nEND\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(2, units.size());
        assertEquals(SqlUnit.Kind.SQL, units.get(0).kind);
        assertTrue("the whole IF block must stay one unit", units.get(0).text.contains("INSERT [dbo].[T] VALUES (2)"));
        assertEquals("must not be marked splittable (it is a block, not a bare DML statement)",
                false, units.get(0).splittable);
    }

    @Test
    public void identityInsertOnStandsAloneEvenBetweenInsertRuns() {
        String sql = "SET IDENTITY_INSERT [dbo].[T] ON\n"
                + "INSERT [dbo].[T] VALUES (1)\n"
                + "INSERT [dbo].[T] VALUES (2)\n"
                + "SET IDENTITY_INSERT [dbo].[T] OFF\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(5, units.size()); // ON, INSERT, INSERT, OFF, GO
        assertEquals("SET IDENTITY_INSERT [dbo].[T] ON", units.get(0).text);
        assertEquals(false, units.get(0).splittable);
        assertTrue(units.get(1).splittable);
        assertTrue(units.get(2).splittable);
        assertEquals("SET IDENTITY_INSERT [dbo].[T] OFF", units.get(3).text);
        assertEquals(SqlUnit.Kind.BATCH_SEPARATOR, units.get(4).kind);
    }

    @Test
    public void ignoresDashDashCommentInsideLine() {
        String sql = "INSERT [dbo].[T] VALUES (1) -- trailing comment\nGO\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(2, units.size());
        assertEquals(SqlUnit.Kind.SQL, units.get(0).kind);
    }

    @Test
    public void goWithTrailingCountIsRecognised() {
        String sql = "INSERT [dbo].[T] VALUES (1)\nGO 5\n";
        List<SqlUnit> units = scan(sql);
        assertEquals(2, units.size());
        assertEquals(SqlUnit.Kind.BATCH_SEPARATOR, units.get(1).kind);
    }
}
