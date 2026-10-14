import 'package:flutter_test/flutter_test.dart';
import 'package:rehnuma/channel/rehnuma_channel.dart';
import 'package:rehnuma/l10n/strings.dart';

void main() {
  test('RehnumaState parses the EventChannel map', () {
    final s = RehnumaState.fromMap({
      'state': 'POINTING',
      'goalText': 'Turn on WiFi',
      'step': 2,
      'caption': 'Tap the WiFi switch',
    });
    expect(s.state, 'POINTING');
    expect(s.goalText, 'Turn on WiFi');
    expect(s.step, 2);
    expect(s.caption, 'Tap the WiFi switch');
    expect(s.error, isNull);
  });

  test('missing state falls back to IDLE', () {
    expect(RehnumaState.fromMap({}).state, 'IDLE');
  });

  test('five quick tasks from SPEC 8.4', () {
    expect(S.quickTasks.map((t) => t.en), [
      'Send a photo on WhatsApp',
      'Make the text on my phone bigger',
      'Turn on WiFi',
      'Call someone',
      'Top up my mobile balance',
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

  test('English is the default; Urdu is picked only on request', () {
    Lang.set(null);
    expect(S.homeStart.text, 'Start Pointly');
    Lang.set('ur');
    expect(S.homeStart.text, 'پوائنٹلی چالو کریں');
    Lang.set('something else');
    expect(Lang.isUrdu, isFalse);
  });

  test('English strings contain no Urdu script', () {
    final urduScript = RegExp(r'[\u0600-\u06FF]');
    final all = <Bi>[
      S.onb1Title, S.onb1Body, S.onb2Title, S.promise1, S.promise2, S.promise3, S.onb3Title, S.onb3Body,
      S.homeStart, S.homeStop, S.homeQuickTasks, S.homeServiceOff, S.setTitle, S.setLanguage, S.permTitle,
      ...S.quickTasks,
    ];
    for (final b in all) {
      expect(urduScript.hasMatch(b.en), isFalse, reason: b.en);
      expect(urduScript.hasMatch(b.ur), isTrue, reason: b.ur);
    }
  });
}
