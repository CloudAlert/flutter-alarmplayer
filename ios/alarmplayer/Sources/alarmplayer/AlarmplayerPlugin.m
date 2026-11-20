#import "./include/alarmplayer/AlarmplayerPlugin.h"
#import <AVFoundation/AVFoundation.h>

@interface AlarmplayerPlugin () <AVAudioPlayerDelegate>
// Retained so playback-completion callbacks can be invoked after play().
@property (nonatomic, strong) FlutterMethodChannel *channel;
@property (nonatomic, strong) AVAudioPlayer *player;
// Mirrors the Android "callback" argument: invoke "callback" on the channel
// when non-looping playback finishes.
@property (nonatomic, assign) BOOL invokeCallbackOnFinish;
@end

@implementation AlarmplayerPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
      methodChannelWithName:@"alarmplayer"
            binaryMessenger:[registrar messenger]];
  AlarmplayerPlugin* instance = [[AlarmplayerPlugin alloc] init];
  instance.channel = channel;
  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  if ([@"getPlatformVersion" isEqualToString:call.method]) {
    result([@"iOS " stringByAppendingString:[[UIDevice currentDevice] systemVersion]]);
  } else if ([@"play" isEqualToString:call.method]) {
    [self handlePlayCall:call result:result];
  } else if ([@"stop" isEqualToString:call.method]) {
    [self handleStopCall:result];
  } else if ([@"playing" isEqualToString:call.method]) {
    result(@(self.player.isPlaying));
  } else {
    result(FlutterMethodNotImplemented);
  }
}

- (void)handlePlayCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  if (![call.arguments isKindOfClass:[NSDictionary class]]) {
    result([FlutterError errorWithCode:@"invalid_arguments"
                               message:@"play requires a map of arguments"
                               details:nil]);
    return;
  }
  NSDictionary *arguments = call.arguments;
  NSString *assetKey = arguments[@"url"];
  double volume = [arguments[@"volume"] doubleValue];
  BOOL loop = [arguments[@"loop"] boolValue];
  BOOL hasCallback = [arguments[@"callback"] boolValue];

  if (assetKey.length == 0) {
    result([FlutterError errorWithCode:@"invalid_arguments"
                               message:@"play requires a non-empty url"
                               details:nil]);
    return;
  }

  // Resolve the Flutter asset key to a path in the app bundle; fall back to an
  // absolute path for callers that hand over a file path directly.
  NSString *assetPath = [FlutterDartProject lookupKeyForAsset:assetKey];
  NSURL *fileURL = nil;
  if (assetPath.length > 0) {
    fileURL = [NSURL fileURLWithPath:assetPath];
  } else if ([assetKey hasPrefix:@"/"]) {
    fileURL = [NSURL fileURLWithPath:assetKey];
  }
  if (fileURL == nil) {
    result([FlutterError errorWithCode:@"asset_not_found"
                               message:[NSString stringWithFormat:@"Could not resolve alarm asset '%@'", assetKey]
                               details:nil]);
    return;
  }

  // Playback category so the alarm is audible even with the silent switch on.
  NSError *error = nil;
  AVAudioSession *session = [AVAudioSession sharedInstance];
  if (![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault options:0 error:&error]) {
    result([FlutterError errorWithCode:@"audio_session_failed"
                               message:[NSString stringWithFormat:@"Could not configure the audio session: %@", error.localizedDescription]
                               details:nil]);
    return;
  }
  [session setActive:YES error:nil];  // Best effort; playback may still start.

  // Interrupt any in-flight playback first, mirroring the Android behavior.
  [self.player stop];
  self.player = nil;
  self.invokeCallbackOnFinish = NO;

  AVAudioPlayer *player = [[AVAudioPlayer alloc] initWithContentsOfURL:fileURL error:&error];
  if (player == nil) {
    result([FlutterError errorWithCode:@"audio_load_failed"
                               message:[NSString stringWithFormat:@"Could not load alarm audio: %@", error.localizedDescription]
                               details:nil]);
    return;
  }
  player.delegate = self;
  // Relative to the system output volume: iOS exposes no per-stream volume an
  // app may write, so the raised-STREAM_ALARM behavior stays Android-only.
  player.volume = volume;
  player.numberOfLoops = loop ? -1 : 0;
  self.invokeCallbackOnFinish = hasCallback;
  self.player = player;

  if (![player play]) {
    self.player = nil;
    self.invokeCallbackOnFinish = NO;
    result([FlutterError errorWithCode:@"audio_start_failed"
                               message:@"Could not start alarm playback"
                               details:nil]);
    return;
  }
  result(nil);
}

- (void)handleStopCall:(FlutterResult)result {
  [self.player stop];
  self.player = nil;
  self.invokeCallbackOnFinish = NO;
  result(nil);
}

- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag {
  if (player != self.player) {
    return;  // Completion of a player that was already replaced.
  }
  BOOL shouldInvokeCallback = self.invokeCallbackOnFinish;
  // AVAudioPlayer delivers delegate events off the main thread; channel calls
  // belong on the platform thread.
  dispatch_async(dispatch_get_main_queue(), ^{
    if (player != self.player) {
      return;  // A newer play() or stop() replaced this player meanwhile.
    }
    self.player = nil;
    self.invokeCallbackOnFinish = NO;
    if (shouldInvokeCallback) {
      [self.channel invokeMethod:@"callback" arguments:nil];
    }
  });
}

- (void)dealloc {
  [_player stop];
}

@end
