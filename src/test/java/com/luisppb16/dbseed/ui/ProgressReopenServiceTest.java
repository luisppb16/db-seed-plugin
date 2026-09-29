/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * *****************************************************************************
 */

package com.luisppb16.dbseed.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.intellij.openapi.project.Project;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProgressReopenServiceTest {

  private ProgressReopenService service;

  @BeforeEach
  void setUp() {
    final Project project = Mockito.mock(Project.class);
    service = new ProgressReopenService(project);
  }

  @Test
  void isHidden_initiallyFalse() {
    // Then
    assertThat(service.isHidden()).isFalse();
  }

  @Test
  void hide_remembersThatTheWindowIsHidden() {
    // When
    service.hide(() -> {});

    // Then
    assertThat(service.isHidden()).isTrue();
  }

  @Test
  void reopen_runsTheActionOnceAndForgetsIt() {
    // Given
    final AtomicInteger reopens = new AtomicInteger();
    service.hide(reopens::incrementAndGet);

    // When
    service.reopen();
    service.reopen();

    // Then
    assertThat(reopens).hasValue(1);
    assertThat(service.isHidden()).isFalse();
  }

  @Test
  void show_forgetsTheActionWithoutRunningIt() {
    // Given
    final AtomicInteger reopens = new AtomicInteger();
    service.hide(reopens::incrementAndGet);

    // When
    service.show();
    service.reopen();

    // Then
    assertThat(reopens).hasValue(0);
    assertThat(service.isHidden()).isFalse();
  }

  @Test
  void hide_secondAction_replacesTheFirst() {
    // Given
    final AtomicInteger first = new AtomicInteger();
    final AtomicInteger second = new AtomicInteger();
    service.hide(first::incrementAndGet);

    // When
    service.hide(second::incrementAndGet);
    service.reopen();

    // Then
    assertThat(first).hasValue(0);
    assertThat(second).hasValue(1);
  }

  @Test
  void hide_nullAction_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(() -> service.hide(null))
        .withMessage("Reopen action cannot be null");
  }

  @Test
  void constructor_nullProject_failsFast() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ProgressReopenService(null))
        .withMessage("Project cannot be null");
  }
}
