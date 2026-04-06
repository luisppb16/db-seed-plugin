/*
 */

package com.luisppb16.dbseed.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
@State(
    name = "DbSeedProjectState",
    storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public class DbSeedProjectState implements PersistentStateComponent<DbSeedProjectState> {

    private List<ConnectionProfile> profiles = new ArrayList<>();
    private String activeProfileName = "Default";

    public static DbSeedProjectState getInstance(@NotNull Project project) {
        return project.getService(DbSeedProjectState.class);
    }

    @Nullable
    @Override
    public DbSeedProjectState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DbSeedProjectState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}

