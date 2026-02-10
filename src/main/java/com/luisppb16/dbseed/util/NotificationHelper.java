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

public final class NotificationHelper {

  private static final String NOTIFICATION_GROUP_ID = "DBSeed4SQL";
  private static final NotificationGroup NOTIFICATION_GROUP;

  static {
    NOTIFICATION_GROUP = NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP_ID);
  }

  private NotificationHelper() {
    // Utility class
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
