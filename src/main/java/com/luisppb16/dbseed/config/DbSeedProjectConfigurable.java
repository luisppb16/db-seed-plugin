/*
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class DbSeedProjectConfigurable implements Configurable {

    private final Project project;

    public DbSeedProjectConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "DBSeed Profiles";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return new JPanel(); // Basic placeholder
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
    }
}

