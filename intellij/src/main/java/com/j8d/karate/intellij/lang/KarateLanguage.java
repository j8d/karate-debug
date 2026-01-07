package com.j8d.karate.intellij.lang;

import com.intellij.lang.Language;

/**
 * Language definition for Karate feature files.
 */
public class KarateLanguage extends Language {
    
    public static final KarateLanguage INSTANCE = new KarateLanguage();
    
    private KarateLanguage() {
        super("Karate");
    }
}

