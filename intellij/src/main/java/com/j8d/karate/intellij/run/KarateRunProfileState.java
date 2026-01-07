package com.j8d.karate.intellij.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Run profile state for Karate executions.
 * This is used for both run and debug modes.
 */
public class KarateRunProfileState implements RunProfileState {
    
    private final KarateRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    
    public KarateRunProfileState(@NotNull KarateRunConfiguration configuration,
                                  @NotNull ExecutionEnvironment environment) {
        this.configuration = configuration;
        this.environment = environment;
    }
    
    @Override
    public @Nullable ExecutionResult execute(Executor executor, @NotNull ProgramRunner<?> runner)
            throws ExecutionException {
        // This will be implemented when we add debug infrastructure
        // For now, return null to indicate not yet implemented
        throw new ExecutionException("Karate execution not yet implemented. Debug support coming soon.");
    }
    
    public KarateRunConfiguration getConfiguration() {
        return configuration;
    }
    
    public ExecutionEnvironment getEnvironment() {
        return environment;
    }
}

