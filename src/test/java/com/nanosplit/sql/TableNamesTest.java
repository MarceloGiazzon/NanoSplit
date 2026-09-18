package com.nanosplit.sql;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TableNamesTest {

    @Test
    public void extractsBracketedSchemaAndTable() {
        assertEquals("dbo.Entity", TableNames.of(
                "INSERT [dbo].[Entity] ([Id]) VALUES (1)", null));
    }

    @Test
    public void extractsUnbracketedName() {
        assertEquals("Foo", TableNames.of("INSERT Foo (Id) VALUES (1)", null));
    }

    @Test
    public void handlesInsertInto() {
        assertEquals("dbo.Foo", TableNames.of("INSERT INTO [dbo].[Foo] VALUES (1)", null));
    }

    @Test
    public void returnsNullForNonInsert() {
        assertNull(TableNames.of("UPDATE [dbo].[Foo] SET x = 1", null));
    }

    @Test
    public void memoizesRepeatedLookups() {
        java.util.Map<String, String> memo = TableNames.newMemo();
        String a = TableNames.of("INSERT [dbo].[Foo] VALUES (1)", memo);
        String b = TableNames.of("INSERT [dbo].[Foo] VALUES (999)", memo);
        assertEquals("dbo.Foo", a);
        assertEquals(a, b);
    }
}
