package com.nanosplit.sql;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IdempotentDdlTest {

    @Test
    public void wrapsCreateDatabase() {
        String out = IdempotentDdl.wrap("CREATE DATABASE [MyDb]\r\n ON PRIMARY (NAME = N'x')");
        assertTrue(out.startsWith("IF DB_ID(N'MyDb') IS NULL\r\nBEGIN\r\n"));
        assertTrue(out.contains("CREATE DATABASE [MyDb]"));
        assertTrue(out.endsWith("END"));
    }

    @Test
    public void wrapsCreateTableWithSchemaQualifiedName() {
        String out = IdempotentDdl.wrap("CREATE TABLE [dbo].[Foo](\r\n\t[Id] [int] NOT NULL\r\n)");
        assertTrue(out.startsWith("IF OBJECT_ID(N'[dbo].[Foo]', N'U') IS NULL\r\nBEGIN\r\n"));
        assertTrue(out.contains("CREATE TABLE [dbo].[Foo]("));
    }

    @Test
    public void wrapsCreateIndex() {
        String out = IdempotentDdl.wrap("CREATE NONCLUSTERED INDEX [IX_Foo] ON [dbo].[Foo]([Id] ASC)");
        assertTrue(out.contains("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Foo' "
                + "AND object_id = OBJECT_ID(N'[dbo].[Foo]'))"));
        assertTrue(out.contains("CREATE NONCLUSTERED INDEX [IX_Foo] ON [dbo].[Foo]([Id] ASC)"));
    }

    @Test
    public void leavesPlainInsertUntouched() {
        String sql = "INSERT [dbo].[Foo] ([Id]) VALUES (1)";
        assertEquals(sql, IdempotentDdl.wrap(sql));
    }

    @Test
    public void leavesUseAndSetUntouched() {
        assertEquals("USE [MyDb]", IdempotentDdl.wrap("USE [MyDb]"));
        assertEquals("SET ANSI_NULLS ON", IdempotentDdl.wrap("SET ANSI_NULLS ON"));
    }

    @Test
    public void escapesSingleQuoteInIdentifier() {
        String out = IdempotentDdl.wrap("CREATE DATABASE [My'Db]\r\nON PRIMARY");
        assertTrue(out.contains("DB_ID(N'My''Db')"));
    }

    @Test
    public void skipsSsmsObjectCommentBeforeCreateDatabase() {
        // This is the exact shape SSMS emits: the object comment and the
        // CREATE statement are glued into one unit with no blank line between.
        String out = IdempotentDdl.wrap("/****** Objeto:  Database [MyDb]    Data do Script: 1/1/2026 ******/\r\n"
                + "CREATE DATABASE [MyDb]\r\n CONTAINMENT = NONE");
        assertTrue(out.startsWith("/****** Objeto:"));
        assertTrue(out.contains("IF DB_ID(N'MyDb') IS NULL\r\nBEGIN\r\nCREATE DATABASE [MyDb]"));
        assertTrue(out.endsWith("END"));
    }

    @Test
    public void skipsSsmsObjectCommentBeforeCreateTable() {
        String out = IdempotentDdl.wrap("/****** Objeto:  Table [dbo].[Foo]    Data do Script: 1/1/2026 ******/\r\n"
                + "CREATE TABLE [dbo].[Foo](\r\n[Id] [int] NOT NULL\r\n)");
        assertTrue(out.startsWith("/****** Objeto:"));
        assertTrue(out.contains("IF OBJECT_ID(N'[dbo].[Foo]', N'U') IS NULL\r\nBEGIN\r\nCREATE TABLE [dbo].[Foo]("));
    }

    @Test
    public void preservesLeadingWhitespace() {
        String out = IdempotentDdl.wrap("  CREATE TABLE [dbo].[Foo](\r\n[Id] [int]\r\n)");
        assertTrue(out.startsWith("  IF OBJECT_ID"));
    }
}
