/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DriverLoaderTest {

    @Test
    @DisplayName("Should create DriverInitializationException with message and cause")
    void driverInitializationException() {
        IOException cause = new IOException("Disk full");
        DriverInitializationException ex = new DriverInitializationException("Error creating dir", cause);

        assertThat(ex.getMessage()).isEqualTo("Error creating dir");
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should create DriverLoadingException with message and cause")
    void driverLoadingExceptionWithCause() {
        RuntimeException cause = new RuntimeException("Validation failed");
        DriverLoadingException ex = new DriverLoadingException("Loading failed", cause);
        assertThat(ex.getMessage()).isEqualTo("Loading failed");
        assertThat(ex.getCause()).isEqualTo(cause);
    }
}
