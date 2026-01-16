package com.j8d.karate.intellij.run;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

/**
 * Options for Karate run configurations.
 */
public class KarateRunConfigurationOptions extends RunConfigurationOptions {
    
    private final StoredProperty<String> featureFile = string("")
        .provideDelegate(this, "featureFile");
    
    private final StoredProperty<String> scenarioName = string("")
        .provideDelegate(this, "scenarioName");
    
    private final StoredProperty<String> karateEnv = string("")
        .provideDelegate(this, "karateEnv");
    
    private final StoredProperty<String> karateOptions = string("")
        .provideDelegate(this, "karateOptions");
    
    private final StoredProperty<Integer> scenarioLine = property(0)
        .provideDelegate(this, "scenarioLine");
    
    public String getFeatureFile() {
        return featureFile.getValue(this);
    }
    
    public void setFeatureFile(String value) {
        featureFile.setValue(this, value);
    }
    
    public String getScenarioName() {
        return scenarioName.getValue(this);
    }
    
    public void setScenarioName(String value) {
        scenarioName.setValue(this, value);
    }
    
    public String getKarateEnv() {
        return karateEnv.getValue(this);
    }
    
    public void setKarateEnv(String value) {
        karateEnv.setValue(this, value);
    }
    
    public String getKarateOptions() {
        return karateOptions.getValue(this);
    }
    
    public void setKarateOptions(String value) {
        karateOptions.setValue(this, value);
    }
    
    public int getScenarioLine() {
        return scenarioLine.getValue(this);
    }
    
    public void setScenarioLine(int value) {
        scenarioLine.setValue(this, value);
    }
}

