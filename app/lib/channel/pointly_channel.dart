import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class PointlyState {
  const PointlyState({
    required this.state,
    this.goalText,
    this.step,
    this.caption,
    this.error,
  });

  final String state;
  final String? goalText;
  final int? step;
  final String? caption;
  final String? error;

  static const idle = PointlyState(state: 'IDLE');

  factory PointlyState.fromMap(Map<dynamic, dynamic> m) => PointlyState(
        state: (m['state'] as String?) ?? 'IDLE',
        goalText: m['goalText'] as String?,
        step: m['step'] as int?,
        caption: m['caption'] as String?,
        error: m['error'] as String?,
      );
}

class PermissionStatus {
  const PermissionStatus({
    required this.accessibility,
    required this.microphone,
    required this.notifications,
  });

  final bool accessibility;
  final bool microphone;
  final bool notifications;

  bool get allGranted => accessibility && microphone && notifications;

  static const none =
      PermissionStatus(accessibility: false, microphone: false, notifications: false);
}

class PointlyChannel {
  PointlyChannel._();

  static const _control = MethodChannel('pointly/control');
  static const _events = EventChannel('pointly/events');

  static Stream<PointlyState>? _stateStream;

  @visibleForTesting
  static void resetForTest() => _stateStream = null;

  static Stream<PointlyState> get states => _stateStream ??= _events
      .receiveBroadcastStream()
      .map((e) => PointlyState.fromMap(e as Map<dynamic, dynamic>))
      .asBroadcastStream();

  static Future<bool> isServiceEnabled() async =>
      (await _control.invokeMethod<bool>('isServiceEnabled')) ?? false;

  static Future<void> openAccessibilitySettings() =>
      _control.invokeMethod<void>('openAccessibilitySettings');

  static Future<PermissionStatus> getPermissions() async {
    final m = await _control.invokeMapMethod<String, dynamic>('getPermissions');
    if (m == null) return PermissionStatus.none;
    return PermissionStatus(
      accessibility: m['accessibility'] == true,
      microphone: m['microphone'] == true,
      notifications: m['notifications'] == true,
    );
  }

  static Future<void> requestPermission(String name) =>
      _control.invokeMethod<void>('requestPermission', {'name': name});

  static Future<void> showBubble() => _control.invokeMethod<void>('showBubble');

  static Future<void> hideBubble() => _control.invokeMethod<void>('hideBubble');

  static Future<bool> isBubbleVisible() async =>
      (await _control.invokeMethod<bool>('isBubbleVisible')) ?? false;

  static Future<void> startQuickTask(String goal) =>
      _control.invokeMethod<void>('startQuickTask', {'goal': goal});

  static Future<void> setConfig(Map<String, Object?> values) =>
      _control.invokeMethod<void>('setConfig', values);

  static Future<Map<String, dynamic>> getConfig() async =>
      (await _control.invokeMapMethod<String, dynamic>('getConfig')) ?? <String, dynamic>{};

  static Future<Map<String, dynamic>> getMetricsSummary() async =>
      (await _control.invokeMapMethod<String, dynamic>('getMetricsSummary')) ??
      <String, dynamic>{};

  static Future<String?> exportMetrics() => _control.invokeMethod<String>('exportMetrics');

  static Future<void> resetLearning() => _control.invokeMethod<void>('resetLearning');

  static Future<void> setDebugBoxes(bool enabled) =>
      _control.invokeMethod<void>('setDebugBoxes', {'enabled': enabled});

  static Future<void> saveSnapshot({int delayMs = 5000}) =>
      _control.invokeMethod<void>('saveSnapshot', {'delayMs': delayMs});

  static Future<Map<String, dynamic>> checkBackend() async =>
      (await _control.invokeMapMethod<String, dynamic>('checkBackend')) ?? <String, dynamic>{};

  static Future<PointlyState> getState() async {
    final m = await _control.invokeMethod<Map<dynamic, dynamic>>('getState');
    return m == null ? PointlyState.idle : PointlyState.fromMap(m);
  }
}
