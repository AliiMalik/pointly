import 'package:flutter/services.dart';

/// Snapshot of the guidance session, sent by the Kotlin core on every state change (SPEC 10.2).
class RehnumaState {
  const RehnumaState({
    required this.state,
    this.goalText,
    this.step,
    this.captionRoman,
    this.error,
  });

  final String state;
  final String? goalText;
  final int? step;
  final String? captionRoman;
  final String? error;

  static const idle = RehnumaState(state: 'IDLE');

  factory RehnumaState.fromMap(Map<dynamic, dynamic> m) => RehnumaState(
        state: (m['state'] as String?) ?? 'IDLE',
        goalText: m['goalText'] as String?,
        step: m['step'] as int?,
        captionRoman: m['captionRoman'] as String?,
        error: m['error'] as String?,
      );
}

/// Status of the three things Rehnuma needs before it can start.
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

/// Thin wrapper over `rehnuma/control` and `rehnuma/events` (SPEC 10.1, 10.2).
class RehnumaChannel {
  RehnumaChannel._();

  static const _control = MethodChannel('rehnuma/control');
  static const _events = EventChannel('rehnuma/events');

  static Stream<RehnumaState>? _stateStream;

  /// Broadcast stream of session states; the core replays the latest state on listen.
  static Stream<RehnumaState> get states => _stateStream ??= _events
      .receiveBroadcastStream()
      .map((e) => RehnumaState.fromMap(e as Map<dynamic, dynamic>))
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

  /// [name] is `microphone` or `notifications`.
  static Future<void> requestPermission(String name) =>
      _control.invokeMethod<void>('requestPermission', {'name': name});

  /// Throws [PlatformException] with code `SERVICE_OFF` when the accessibility service is off.
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

  /// Opens the share sheet for `metrics.jsonl` and returns the exported file path.
  static Future<String?> exportMetrics() => _control.invokeMethod<String>('exportMetrics');

  static Future<void> resetLearning() => _control.invokeMethod<void>('resetLearning');

  static Future<void> setDebugBoxes(bool enabled) =>
      _control.invokeMethod<void>('setDebugBoxes', {'enabled': enabled});

  /// Reads the app in front after [delayMs] and saves a masked snapshot (SPEC 11).
  static Future<void> saveSnapshot({int delayMs = 5000}) =>
      _control.invokeMethod<void>('saveSnapshot', {'delayMs': delayMs});

  /// Settings "check server": `{reachable, model?, keyOk?, error?}`.
  static Future<Map<String, dynamic>> checkBackend() async =>
      (await _control.invokeMapMethod<String, dynamic>('checkBackend')) ?? <String, dynamic>{};

  static Future<RehnumaState> getState() async {
    final m = await _control.invokeMethod<Map<dynamic, dynamic>>('getState');
    return m == null ? RehnumaState.idle : RehnumaState.fromMap(m);
  }
}
