plugins {
    id("ledgerly.java-library")
    // Sample events under src/testFixtures: the producer's tests and the consumers' tests check against the same files.
    `java-test-fixtures`
}

description = "Event contracts shared by ledger-app and notification-consumer"
