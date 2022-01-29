/*
 * Copyright 2022 Daniel Erat <dan@erat.org>
 * All rights reserved.
 */

package org.erat.nup.test

import org.erat.nup.splitSQL
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseTest {
    @Test fun splitSQLSingle() = assertEquals(
        listOf("DROP TABLE Foo"),
        splitSQL("DROP TABLE Foo;")
    )

    @Test fun splitSQLSingleNoSemicolon() = assertEquals(
        listOf("DROP TABLE Foo"),
        splitSQL("DROP TABLE Foo")
    )

    @Test fun splitSQLMulti() = assertEquals(
        listOf(
            "DROP TABLE Stuff",
            "CREATE TABLE Stuff ( Id INTEGER PRIMARY KEY, Val VARCHAR(256))",
            "CREATE INDEX Val ON Stuff (Val)",
        ),
        splitSQL(
            """
            -- Drop the table first.
            DROP TABLE Stuff;
            -- Create the new table.
            CREATE TABLE Stuff (
              Id INTEGER PRIMARY KEY, -- end-of-line comment
              Val VARCHAR(256));
            CREATE INDEX Val ON Stuff (Val);
            """
        )
    )
}
