import 'package:flutter_test/flutter_test.dart';
import 'package:rehnuma/channel/rehnuma_channel.dart';
import 'package:rehnuma/l10n/strings.dart';

void main() {
  test('RehnumaState parses the EventChannel map', () {
    final s = RehnumaState.fromMap({
      'state': 'POINTING',
      'goalText': 'WiFi on karna hai',
      'step': 2,
      'captionRoman': 'WiFi wala button dabayein',
    });
    expect(s.state, 'POINTING');
    expect(s.goalText, 'WiFi on karna hai');
    expect(s.step, 2);
    expect(s.captionRoman, 'WiFi wala button dabayein');
    expect(s.error, isNull);
  });

  test('missing state falls back to IDLE', () {
    expect(RehnumaState.fromMap({}).state, 'IDLE');
  });

  test('five quick tasks from SPEC 8.4', () {
    expect(S.quickTasks.map((t) => t.goal), [
      'WhatsApp pe photo bhejni hai',
      'Phone ke likhai bari karni hai',
      'WiFi on karna hai',
      'Kisi ko call karni hai',
      'Mobile load karna hai',
    ]);
  });

  test('every session state has a friendly label', () {
    for (final state in [
      'IDLE', 'LISTENING', 'TRANSCRIBING', 'READING_SCREEN', 'SENSITIVE_PAUSE', 'PLANNING',
      'POINTING', 'WAITING_FOR_USER', 'ASKING', 'COMPLETED', 'ERROR',
    ]) {
      expect(S.stateLabel(state).en, isNot(state), reason: state);
    }
  });
}
