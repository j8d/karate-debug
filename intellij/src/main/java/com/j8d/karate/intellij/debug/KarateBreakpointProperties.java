package com.j8d.karate.intellij.debug;

import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Properties for Karate breakpoints.
 * Currently minimal, can be extended for conditional breakpoints.
 */
public class KarateBreakpointProperties extends XBreakpointProperties<KarateBreakpointProperties> {
    
    @Override
    public @Nullable KarateBreakpointProperties getState() {
        return this;
    }
    
    @Override
    public void loadState(@Nullable KarateBreakpointProperties state) {
        // No state to load yet
    }
}

