package com.adnan.glasslauncher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant notification access, which Android requires before
 * a launcher may read other apps' media sessions. It does not read notifications.
 */
public class MediaListenerService extends NotificationListenerService {
}
