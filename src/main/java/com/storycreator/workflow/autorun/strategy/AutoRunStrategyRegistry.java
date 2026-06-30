package com.storycreator.workflow.autorun.strategy;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AutoRunStrategyRegistry {

    private final Map<String, AutoRunStrategy> strategies;

    public AutoRunStrategyRegistry(List<AutoRunStrategy> beans) {
        this.strategies = beans.stream()
                .collect(Collectors.toMap(AutoRunStrategy::getName, Function.identity()));
    }

    public AutoRunStrategy resolve(String name) {
        String key = (name == null || name.isBlank()) ? "DEFAULT" : name;
        AutoRunStrategy strategy = strategies.getOrDefault(key, strategies.get("DEFAULT"));
        if (strategy == null) {
            throw new IllegalStateException("No AutoRunStrategy found for key '" + key
                    + "' and no DEFAULT strategy registered. Available: " + strategies.keySet());
        }
        return strategy;
    }
}
