package com.settlement.pacing.api.monitoring;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

@Component
public class StorageFailureClassifier {
    private static final int MAX_CAUSE_DEPTH = 32;

    public StorageType classify(Throwable exception) {
        if (exception == null) {
            return StorageType.UNKNOWN;
        }

        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = exception;

        for (int depth = 0;
             current != null
                     && depth < MAX_CAUSE_DEPTH
                     && visited.add(current);
             depth++) {
            String className = current.getClass()
                    .getName()
                    .toLowerCase(Locale.ROOT);

            if (isRedis(className)) {
                return StorageType.REDIS;
            }

            if (isPostgresql(className)) {
                return StorageType.POSTGRESQL;
            }

            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT)
                    .contains("redis")) {
                return StorageType.REDIS;
            }

            current = current.getCause();
        }

        return StorageType.UNKNOWN;
    }

    private boolean isRedis(String className) {
        return className.contains("redis")
                || className.startsWith("io.lettuce.")
                || className.startsWith("redis.clients.");
    }

    private boolean isPostgresql(String className) {
        return className.startsWith("org.postgresql.")
                || className.startsWith("java.sql.")
                || className.startsWith("com.zaxxer.hikari.")
                || className.contains("cannotcreatetransaction")
                || className.contains("jpatransactionmanager");
    }
}
