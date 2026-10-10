package com.diet.service.jev;

import java.util.Map;

/** Calls Jev's System One API with typed questions. */
@FunctionalInterface
public interface JevClient {
    JevResponse systemOne(Object state, Map<String, Object> questions);
}
