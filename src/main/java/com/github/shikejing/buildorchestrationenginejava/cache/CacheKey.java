package com.github.shikejing.buildorchestrationenginejava.cache;

import java.util.Map;

public record CacheKey(
        String value,
        Map<String, String> components
) {
}
