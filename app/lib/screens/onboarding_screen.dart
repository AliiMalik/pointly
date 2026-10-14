import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../widgets/bilingual.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _pages = PageController();
  int _page = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  Future<void> _next() async {
    if (_page < 2) {
      await _pages.nextPage(duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
      return;
    }
    await PointlyChannel.setConfig({'onboarded': true});
    if (!mounted) return;
    Navigator.of(context).pushReplacementNamed(Routes.permissions);
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final green = Theme.of(context).colorScheme.primary;
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: PageView(
                controller: _pages,
                onPageChanged: (i) => setState(() => _page = i),
                children: [
                  _Page(
                    icon: Icons.touch_app_rounded,
                    title: S.onb1Title,
                    child: Column(
                      children: [
                        BiText(S.onb1Body, size: 20, center: true, bold: false),
                        const SizedBox(height: 32),
                        BiText(S.onbLanguage, size: 18, center: true),
                        const SizedBox(height: 8),
                        SegmentedButton<String>(
                          style: const ButtonStyle(minimumSize: WidgetStatePropertyAll(Size(0, 56))),
                          segments: [
                            ButtonSegment(value: Lang.en, label: Text(S.setEnglish.en, style: const TextStyle(fontSize: 18))),
                            ButtonSegment(value: Lang.ur, label: Text(S.setUrdu.ur, style: const TextStyle(fontSize: 18))),
                          ],
                          selected: {Lang.code.value},
                          onSelectionChanged: (s) {
                            Lang.set(s.first);
                            PointlyChannel.setConfig({'language': s.first});
                          },
                        ),
                      ],
                    ),
                  ),
                  _Page(
                    icon: Icons.verified_user_rounded,
                    title: S.onb2Title,
                    child: Column(
                      children: const [
                        _Promise(S.promise1, Icons.back_hand_rounded),
                        _Promise(S.promise2, Icons.visibility_rounded),
                        _Promise(S.promise3, Icons.lock_rounded),
                      ],
                    ),
                  ),
                  _Page(
                    icon: Icons.school_rounded,
                    title: S.onb3Title,
                    child: BiText(S.onb3Body, size: 20, center: true, bold: false),
                  ),
                ],
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(
                3,
                (i) => Container(
                  margin: const EdgeInsets.all(6),
                  width: i == _page ? 28 : 12,
                  height: 12,
                  decoration: BoxDecoration(
                    color: i == _page ? green : Colors.black26,
                    borderRadius: BorderRadius.circular(6),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(20),
              child: BigButton(
                label: _page < 2 ? S.next : S.start,
                icon: _page < 2 ? Icons.arrow_forward_rounded : Icons.check_rounded,
                onPressed: _next,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.icon, required this.title, required this.child});

  final IconData icon;
  final Bi title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 48, 24, 24),
      child: Column(
        children: [
          Icon(icon, size: 96, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 24),
          BiText(title, size: 28, center: true),
          const SizedBox(height: 24),
          child,
        ],
      ),
    );
  }
}

class _Promise extends StatelessWidget {
  const _Promise(this.text, this.icon);

  final Bi text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(icon, size: 36, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 16),
            Expanded(child: BiText(text, size: 20)),
          ],
        ),
      ),
    );
  }
}
