package com.observability.lab.shared.chaos;

import java.time.Duration;

/**
 * The database faults, which each service has to supply itself because the SQL differs.
 *
 * <p>There is no portable way to say "sleep" in SQL. PostgreSQL has {@code pg_sleep}, Oracle has
 * {@code DBMS_SESSION.SLEEP}, and a JPA abstraction over both would hide exactly the thing this
 * interface exists to expose. So the contract is here and the statement lives with the service that
 * owns the database.
 *
 * <p>Both operations hold real pool connections, which is the part that matters. A slow query that
 * did not occupy a connection would raise latency and nothing else; occupying one is what turns a
 * slow dependency into pool exhaustion, and pool exhaustion is what turns one slow endpoint into a
 * whole service that stops answering.
 *
 * <p>Note what this deliberately does <em>not</em> cover: a database that is unreachable, slow to
 * respond, or accepting connections without answering. Those are network faults and Toxiproxy
 * already injects them from outside the process — see {@code docs/Simulation.md}. What is here is
 * the complement: the database is perfectly healthy and the <em>application's</em> use of it is the
 * problem.
 */
public interface DatabaseChaos {

    /**
     * Runs one statement that blocks for the given duration while holding a pool connection.
     *
     * <p>Synchronous by contract. The caller decides whether to run it on the request thread or hand
     * it to a pool, and both are useful: on the request thread it is a slow endpoint, in bulk it is
     * pool exhaustion.
     */
    void sleepInDatabase(Duration duration);

    /** Which database this is, for the response body. */
    String databaseName();
}
