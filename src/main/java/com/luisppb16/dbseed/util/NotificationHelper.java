/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

package com.luisppb16.dbseed.util;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Centralized notification utility for the DBSeed plugin ecosystem.
 * <p>
 * This utility class provides a standardized interface for displaying notifications
 * within the IntelliJ IDE environment. It encapsulates the complexity of IntelliJ's
 * notification system and offers a clean, consistent API for different types of
 * user-facing messages. The class utilizes a dedicated notification group to ensure
 * proper categorization and styling of all plugin-generated notifications.
 * </p>
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Managing a centralized notification group for the DBSeed plugin</li>
 *   <li>Providing type-safe methods for error, warning, and information notifications</li>
 *   <li>Abstracting IntelliJ's complex notification API for easier consumption</li>
 *   <li>Ensuring consistent presentation of user feedback across the plugin</li>
 *   <li>Handling null-project scenarios gracefully for application-level notifications</li>
 * </ul>
 * </p>
 * <p>
 * The class follows the singleton pattern through static initialization and is designed
 * as an immutable utility with private constructor to prevent instantiation. All methods
 * are thread-safe and can be called from any context within the IDE.
 * </p>
 */
public final class NotificationHelper {

  private static final String NOTIFICATION_GROUP_ID = "DBSeed4SQL";
  private static final NotificationGroup NOTIFICATION_GROUP;

  static {
    NOTIFICATION_GROUP = NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP_ID);
  }

  private NotificationHelper() {
  }

  public static void notifyError(@Nullable final Project project, @NotNull final String message) {
    final Notification notification = NOTIFICATION_GROUP
        .createNotification("Error", message, NotificationType.ERROR);
    notification.notify(project);
  }

  public static void notifyInfo(@Nullable final Project project, @NotNull final String title, @NotNull final String message) {
    final Notification notification = NOTIFICATION_GROUP
        .createNotification(title, message, NotificationType.INFORMATION);
    notification.notify(project);
  }

  public static void notifyWarning(@Nullable final Project project, @NotNull final String title, @NotNull final String message) {
    final Notification notification = NOTIFICATION_GROUP
        .createNotification(title, message, NotificationType.WARNING);
    notification.notify(project);
  }
}
