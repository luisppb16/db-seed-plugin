/*
 */

package com.luisppb16.dbseed.config;

import lombok.Data;

@Data
public class ConnectionProfile {
    private String name = "Default";
    private String url = "";
    private String user = "";
    private String schema = "";
    private int rowsPerTable = 10;
    private boolean deferred = false;
    private String softDeleteColumns = "";
    private boolean softDeleteUseSchemaDefault = true;
    private String softDeleteValue = "";
    private int numericScale = 2;
}

