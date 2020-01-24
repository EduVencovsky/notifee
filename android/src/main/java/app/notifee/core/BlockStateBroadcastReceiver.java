package app.notifee.core;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.concurrent.futures.ResolvableFuture;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker.Result;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import app.notifee.core.events.BlockStateEvent;
import app.notifee.core.utils.ObjectUtils;

import static app.notifee.core.LicenseManager.logLicenseWarningForEvent;

public class BlockStateBroadcastReceiver extends BroadcastReceiver {
  private final static String TAG = "BlockState";
  private final static String KEY_TYPE = "type";
  private final static String KEY_BLOCKED = "blocked";
  private final static String KEY_CHANNEL_GROUP = "channelOrGroupId";

  @Keep
  public BlockStateBroadcastReceiver() {
  }

  static void doWork(
    Data workData, ResolvableFuture<Result> completer
  ) {
    Logger.v(TAG, "starting background work");

    final boolean blocked = workData.getBoolean(KEY_BLOCKED, false);
    final int type = workData.getInt(KEY_TYPE, BlockStateEvent.TYPE_APP_BLOCKED);
    final ObjectUtils.TypedCallback<Bundle> sendEventCallback = bundle -> {
      final MethodCallResult<Void> methodCallResult = (e, aVoid) -> {
        if (e != null) {
          Logger.e(TAG, "background work failed with error: ", e);
          completer.set(Result.failure());
        } else {
          Logger.v(TAG, "background work completed successfully");
          completer.set(Result.success());
        }
      };

      BlockStateEvent blockStateEvent = new BlockStateEvent(
        type,
        bundle,
        blocked,
        methodCallResult
      );

      EventBus.post(blockStateEvent);
    };

    if (type == BlockStateEvent.TYPE_APP_BLOCKED) {
      sendEventCallback.call(null);
      return;
    }

    MethodCallResult<Bundle> methodCallResult = (e, aBundle) -> {
      if (e != null) {
        Logger.e(TAG, "Failed getting channel or channel group bundle, received error: ", e);
        completer.set(Result.success());
      } else {
        sendEventCallback.call(aBundle);
      }
    };

    String channelOrGroupId = workData.getString(KEY_CHANNEL_GROUP);
    if (type == BlockStateEvent.TYPE_CHANNEL_BLOCKED) {
      Notifee.getInstance().getChannel(channelOrGroupId, methodCallResult);
    } else if (type == BlockStateEvent.TYPE_CHANNEL_GROUP_BLOCKED) {
      Notifee.getInstance().getChannelGroup(channelOrGroupId, methodCallResult);
    } else {
      Logger.e(TAG, "unknown block state work type");
      completer.set(Result.success());
    }
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return;
    }

    String action = intent.getAction();
    if (action == null) {
      return;
    }

    if (LicenseManager.isLicenseInvalid()) {
      logLicenseWarningForEvent("block state");
      return;
    }

    String uniqueWorkId = action;
    Data.Builder workDataBuilder = new Data.Builder();
    workDataBuilder.putString(Worker.KEY_WORK_TYPE, Worker.WORK_TYPE_BLOCK_STATE_RECEIVER);

    switch (action) {
      case NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED:
        workDataBuilder.putInt(KEY_TYPE, BlockStateEvent.TYPE_APP_BLOCKED);
        break;
      case NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED:
        workDataBuilder.putInt(KEY_TYPE, BlockStateEvent.TYPE_CHANNEL_BLOCKED);
        String channelId = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID);
        workDataBuilder.putString(KEY_CHANNEL_GROUP, channelId);
        uniqueWorkId += "." + channelId;
        break;
      case NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED:
        workDataBuilder.putInt(KEY_TYPE, BlockStateEvent.TYPE_CHANNEL_GROUP_BLOCKED);
        String channelGroupId = intent
          .getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID);
        workDataBuilder.putString(KEY_CHANNEL_GROUP, channelGroupId);
        uniqueWorkId += "." + channelGroupId;
        break;
      default:
        Logger.d(TAG, "unknown intent action received, ignoring.");
        return;
    }

    workDataBuilder.putBoolean(KEY_BLOCKED,
      intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)
    );

    // a second delay to debounce events coming from a user spam toggling block states
    OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(Worker.class)
      .setInitialDelay(1, TimeUnit.SECONDS).setInputData(workDataBuilder.build());

    WorkManager.getInstance(ContextHolder.getApplicationContext())
      .enqueueUniqueWork(uniqueWorkId, ExistingWorkPolicy.REPLACE, builder.build());

    Logger.v(TAG, "scheduled new background work with id " + uniqueWorkId);
  }
}