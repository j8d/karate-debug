package com.j8d.karate.intellij;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.j8d.karate.intellij.lang.KarateFileType;
import com.j8d.karate.intellij.lang.KarateLanguage;
import com.j8d.karate.intellij.run.KarateConfigurationType;

/**
 * Basic tests for the Karate Debug IntelliJ plugin.
 * These tests verify that core plugin components are properly registered.
 */
public class KaratePluginTest extends BasePlatformTestCase {

    /**
     * Test that the Karate language is registered.
     */
    public void testKarateLanguageRegistered() {
        assertNotNull("Karate language should be registered", KarateLanguage.INSTANCE);
        assertEquals("Karate", KarateLanguage.INSTANCE.getID());
    }

    /**
     * Test that the Karate file type is registered for .feature files.
     */
    public void testKarateFileTypeRegistered() {
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension("feature");
        assertNotNull("File type for .feature should exist", fileType);
        assertTrue("File type should be KarateFileType", fileType instanceof KarateFileType);
    }

    /**
     * Test that the Karate file type has correct properties.
     */
    public void testKarateFileTypeProperties() {
        KarateFileType fileType = KarateFileType.INSTANCE;
        assertEquals("Karate Feature", fileType.getName());
        assertEquals("feature", fileType.getDefaultExtension());
        assertNotNull("Icon should not be null", fileType.getIcon());
    }

    /**
     * Test that the Karate run configuration type is registered.
     */
    public void testRunConfigurationTypeRegistered() {
        KarateConfigurationType configType = KarateConfigurationType.getInstance();
        assertNotNull("Karate configuration type should be registered", configType);
        assertEquals("Karate Debug", configType.getDisplayName());
    }

    /**
     * Test that a simple .feature file can be created and recognized.
     */
    public void testFeatureFileRecognition() {
        myFixture.configureByText("test.feature", 
            "Feature: Test Feature\n" +
            "\n" +
            "  Scenario: Test Scenario\n" +
            "    Given url 'http://example.com'\n" +
            "    When method get\n" +
            "    Then status 200\n"
        );
        
        assertEquals("karate", myFixture.getFile().getLanguage().getID().toLowerCase());
    }
}

