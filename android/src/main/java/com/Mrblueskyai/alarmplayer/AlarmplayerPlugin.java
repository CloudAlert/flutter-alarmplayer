// made by: Mrblueskyai
package com.Mrblueskyai.alarmplayer;


import androidx.annotation.NonNull;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
// made by: Mrblueskyai
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.*;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.util.Objects;


/** AlarmplayerPlugin */
public class AlarmplayerPlugin implements FlutterPlugin, MethodCallHandler {
  private static final String TAG = "AlarmplayerPlugin";
  private static final String PREFS_FILE = "alarmplayer_volume_guard";
  private static final String PREF_KEY_ORIGINAL_VOLUME = "original_volume";
  private static final String PREF_KEY_RAISED = "volume_raised";
  private static final int NO_SAVED_VOLUME = Integer.MIN_VALUE;

  public Context context;
  private MediaPlayer mediaPlayer;  // Not static - prevents memory leaks
  private boolean isAlarmPlaying;
  private int originalVolume;       // Not static - prevents memory leaks
  private boolean volumeWasRaised;  // True between raising STREAM_ALARM and restoring it
  private MethodChannel channel;
  private final Object lock = new Object();  // For thread safety

  // Process-wide flag: true while any plugin instance in this process has a
  // raised alarm volume in flight. Guards the persisted-state recovery so a
  // second engine attaching mid-playback does not restore the volume under
  // the active alarm.
  private static volatile boolean sRaiseInFlightInProcess = false;



  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "alarmplayer");
    channel.setMethodCallHandler(this);
    context = flutterPluginBinding.getApplicationContext();
    recoverPersistedVolumeState();
  }


  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    try {
      switch (call.method) {
        case "play":
          boolean callback = Boolean.TRUE.equals(call.argument("callback"));
          boolean looping = Boolean.TRUE.equals(call.argument("loop"));
          String url = Objects.requireNonNull(call.argument("url")).toString();
          double volume = Objects.requireNonNull(call.argument("volume"));
          play(url, volume, looping, callback);
          result.success(null);
          break;
        case "playing":
          synchronized (lock) {
            result.success(mediaPlayer != null && mediaPlayer.isPlaying());
          }
          break;
        case "stop":
          stop();
          result.success(null);
          break;
        default:
          result.notImplemented();
      }
    } catch (Exception e) {
      // Proper error handling - propagate errors to Flutter
      Log.e(TAG, "Error in method call: " + call.method, e);
      result.error("PLUGIN_ERROR", e.getMessage(), e.toString());
    }
  }




  private void play(String url, double volume, boolean loop, boolean callback) {
      // Stop any existing playback first (thread-safe)
      synchronized (lock) {
        if (isAlarmPlaying) {
          stopInternal();
        }
      }

      MediaPlayer player = null;
      try {
        player = new MediaPlayer();
        player.setDataSource(url);


        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
          throw new RuntimeException("AudioManager not available");
        }

        // Save original volume for restoration later. Only capture it when no
        // raise is in flight, so a re-play during playback keeps the first
        // original instead of saving the raised volume as "original".
        if (!volumeWasRaised) {
          originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
          // Persist before raising so a process death between the two writes
          // is recoverable (a stale entry pointing at the still-current value
          // is a no-op on recovery).
          persistVolumeState(originalVolume);
          Log.d(TAG, "Original volume: " + originalVolume);
        }
        volumeWasRaised = true;
        sRaiseInFlightInProcess = true;

        // Calculate and set target volume
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int targetVolume = (int) Math.round(maxVolume * volume);
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0);


        // Check if volume was actually set (may be restricted by system)
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
          if (player != null) {
            player.release();
          }
          throw new RuntimeException("Cannot set alarm volume - may be restricted by system settings");
        }

        // Set audio attributes for alarm playback
        player.setAudioAttributes(
          new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        );


        player.setLooping(loop);

        // Prepare the player
        player.prepare();

        // Set completion listener
        final boolean hasCallback = callback;
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
          @Override
          public void onCompletion(MediaPlayer mp) {
            stop();

            if (hasCallback && channel != null) {
              channel.invokeMethod("callback", null);
            }
          }
        });

        // Start playback
        player.start();

        // Update state (thread-safe)
        synchronized (lock) {
          mediaPlayer = player;
          isAlarmPlaying = true;
        }

      } catch (Exception e) {
        // Cleanup on error
        if (player != null) {
          try {
            player.release();
          } catch (Exception ignored) {
            // Ignore cleanup errors
          }
        }

        // Restore volume on error
        restoreVolume();

        Log.e(TAG, "Failed to play alarm", e);
        throw new RuntimeException("Failed to play alarm: " + e.getMessage(), e);
      }
  }



  private void stop() {
    synchronized (lock) {
      stopInternal();
    }
  }

  /**
   * Internal stop method - must be called within synchronized block
   */
  private void stopInternal() {
    try {
      if (mediaPlayer != null) {
        if (mediaPlayer.isPlaying()) {
          mediaPlayer.stop();
        }
        mediaPlayer.release();
        mediaPlayer = null;
      }

      restoreVolume();
      isAlarmPlaying = false;
    } catch (Exception e) {
      Log.e(TAG, "Error stopping alarm", e);
    }
  }

  /**
   * Restore the original alarm volume.
   *
   * Writes only when this player actually raised the stream (guarded by
   * {@code volumeWasRaised}), so a stop() without a prior play() never mutes
   * the stream, and only when the current stream volume differs from the
   * saved original, so a manual volume change made during playback is never
   * clobbered.
   */
  private void restoreVolume() {
    if (!volumeWasRaised) {
      Log.d(TAG, "No alarm volume raise in flight, nothing to restore");
      return;
    }
    try {
      final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (audioManager != null) {
        final int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        if (currentVolume != originalVolume) {
          audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
          Log.d(TAG, "Volume restored to: " + originalVolume);
        } else {
          Log.d(TAG, "Volume already at original: " + originalVolume);
        }
      }
      volumeWasRaised = false;
      sRaiseInFlightInProcess = false;
      clearPersistedVolumeState();
    } catch (Exception e) {
      // Keep the raised state so a later stop() or the persisted-state
      // recovery on the next engine start can still restore the volume.
      Log.e(TAG, "Error restoring volume", e);
    }
  }


  /**
   * Recover the alarm volume if a previous process died while it was raised.
   *
   * The in-RAM original dies with the process, so the persisted copy (written
   * on every raise, cleared on every restore) is the only witness of a leak:
   * a stale raised entry means the previous process never restored. Restore
   * the saved original once, then clear the entry. Skipped while another
   * engine in this process still has a raise in flight; that instance restores
   * and clears on its own teardown, and the entry survives a second process
   * death.
   */
  private void recoverPersistedVolumeState() {
    if (sRaiseInFlightInProcess) {
      Log.d(TAG, "Alarm volume raise in flight in this process, skipping persisted-state recovery");
      return;
    }
    try {
      final SharedPreferences prefs = volumePrefs();
      if (!prefs.getBoolean(PREF_KEY_RAISED, false)) {
        return;
      }
      final int savedOriginal = prefs.getInt(PREF_KEY_ORIGINAL_VOLUME, NO_SAVED_VOLUME);
      // Restore once, then clear regardless of the outcome so the stale state
      // is never replayed on every subsequent engine start.
      prefs.edit().clear().apply();
      if (savedOriginal == NO_SAVED_VOLUME) {
        return;
      }
      final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (audioManager == null) {
        return;
      }
      final int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
      if (currentVolume != savedOriginal) {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedOriginal, 0);
        Log.i(TAG, "Recovered alarm volume from a previous process: " + currentVolume + " -> " + savedOriginal);
      }
    } catch (Exception e) {
      Log.e(TAG, "Error recovering persisted alarm volume", e);
    }
  }


  private SharedPreferences volumePrefs() {
    return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
  }

  /**
   * Persist the original volume before raising it, so a killed process can be
   * recovered on the next engine attach.
   */
  private void persistVolumeState(int savedOriginal) {
    try {
      volumePrefs().edit()
          .putInt(PREF_KEY_ORIGINAL_VOLUME, savedOriginal)
          .putBoolean(PREF_KEY_RAISED, true)
          .apply();
    } catch (Exception e) {
      Log.e(TAG, "Error persisting alarm volume state", e);
    }
  }

  private void clearPersistedVolumeState() {
    try {
      volumePrefs().edit().clear().apply();
    } catch (Exception e) {
      Log.e(TAG, "Error clearing persisted alarm volume state", e);
    }
  }


  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    // Cleanup resources when plugin is detached
    stop();

    if (channel != null) {
      channel.setMethodCallHandler(null);
      channel = null;
    }
  }
}
// made by: Mrblueskyai
