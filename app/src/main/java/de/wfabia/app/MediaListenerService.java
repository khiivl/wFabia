// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose: Android only lets an app list the active media sessions of other apps when it holds notification access,
 * and this service is what that access is granted to.
 */
public class MediaListenerService extends NotificationListenerService {
}
