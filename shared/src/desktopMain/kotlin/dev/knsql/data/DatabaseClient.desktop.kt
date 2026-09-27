package dev.knsql.data

import dev.knsql.jdbc.JdbcDatabaseClient

actual fun databaseClient(): DatabaseClient = JdbcDatabaseClient
