import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../theme/pointly_theme.dart';
import '../widgets/bilingual.dart';
import '../widgets/guide_demo.dart';
import '../widgets/language_picker.dart';
import '../widgets/ui.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  static const _count = 3;
  final _pages = PageController();
  int _page = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  Future<void> _go(int page) async {
    if (Motion.reduced(context)) {
      _pages.jumpToPage(page);
      return;
    }
    await _pages.animateToPage(page, duration: Motion.slow, curve: Curves.easeInOutCubic);
  }

  Future<void> _next() async {
    if (_page < _count - 1) {
      await _go(_page + 1);
      return;
    }
    await PointlyChannel.setConfig({'onboarded': true});
    if (!mounted) return;
    Navigator.of(context).pushReplacementNamed(Routes.permissions);
  }

  void _setLanguage(String code) {
    Lang.set(code);
    PointlyChannel.setConfig({'language': code});
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final last = _page == _count - 1;
    return PopScope(
      canPop: _page == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _go(_page - 1);
      },
      child: Scaffold(
        body: SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                child: _Progress(page: _page, count: _count),
              ),
              Expanded(
                child: PageView(
                  controller: _pages,
                  onPageChanged: (i) => setState(() => _page = i),
                  children: [
                    _Page(
                      art: const GuideDemo(height: 230),
                      title: S.onb1Title,
                      body: S.onb1Body,
                      footer: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SectionLabel(S.onbLanguage),
                          LanguagePicker(onChanged: _setLanguage),
                        ],
                      ),
                    ),
                    _Page(
                      art: const _ArtBadge(icon: Icons.verified_user_rounded),
                      title: S.onb2Title,
                      footer: const Column(
                        children: [
                          _Promise(index: 0, text: S.promise1, icon: Icons.back_hand_rounded),
                          _Promise(index: 1, text: S.promise2, icon: Icons.visibility_rounded),
                          _Promise(index: 2, text: S.promise3, icon: Icons.lock_rounded),
                        ],
                      ),
                    ),
                    const _Page(art: _FadingHelp(), title: S.onb3Title, body: S.onb3Body),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
                child: Row(
                  children: [
                    SmoothSize(
                      child: _page == 0
                          ? const SizedBox.shrink()
                          : Padding(
                              padding: const EdgeInsetsDirectional.only(end: 12),
                              child: TextButton(onPressed: () => _go(_page - 1), child: Text(S.onbBack.text)),
                            ),
                    ),
                    Expanded(
                      child: BigButton(
                        label: last ? S.start : S.next,
                        icon: last ? Icons.check_rounded : Icons.arrow_forward_rounded,
                        onPressed: _next,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Progress extends StatelessWidget {
  const _Progress({required this.page, required this.count});

  final int page;
  final int count;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: S.onbStep(page + 1, count).text,
      child: Row(
        children: [
          for (var i = 0; i < count; i++) ...[
            if (i > 0) const SizedBox(width: 6),
            Expanded(
              flex: i == page ? 3 : 1,
              child: AnimatedContainer(
                duration: Motion.of(context, Motion.base),
                curve: Motion.enter,
                height: 6,
                decoration: BoxDecoration(
                  color: i <= page ? Palette.brand : Palette.line,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.art, required this.title, this.body, this.footer});

  final Widget art;
  final Bi title;
  final Bi? body;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(child: art),
          const SizedBox(height: 28),
          Reveal(
            index: 0,
            child: BiText(title, size: t.displaySmall!.fontSize!, color: Palette.ink),
          ),
          if (body != null) ...[
            const SizedBox(height: 12),
            Reveal(index: 1, child: BiText(body!, size: 18, bold: false, color: Palette.inkSoft)),
          ],
          if (footer != null) ...[const SizedBox(height: 28), Reveal(index: 2, child: footer!)],
        ],
      ),
    );
  }
}

class _ArtBadge extends StatelessWidget {
  const _ArtBadge({required this.icon});

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: SizedBox(
        height: 150,
        child: Center(
          child: Container(
            width: 132,
            height: 132,
            decoration: const BoxDecoration(color: Palette.brandSoft, shape: BoxShape.circle),
            alignment: Alignment.center,
            child: Container(
              width: 84,
              height: 84,
              decoration: BoxDecoration(
                color: Palette.brand,
                borderRadius: BorderRadius.circular(26),
                boxShadow: [
                  BoxShadow(color: Palette.brand.withValues(alpha: 0.3), blurRadius: 24, offset: const Offset(0, 10)),
                ],
              ),
              child: Icon(icon, color: Colors.white, size: 42),
            ),
          ),
        ),
      ),
    );
  }
}

class _Promise extends StatelessWidget {
  const _Promise({required this.index, required this.text, required this.icon});

  final int index;
  final Bi text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Reveal(
        index: 2 + index,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                IconBadge(icon),
                const SizedBox(width: 14),
                Expanded(child: BiText(text, size: 18, color: Palette.ink)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FadingHelp extends StatelessWidget {
  const _FadingHelp();

  @override
  Widget build(BuildContext context) {
    const steps = [
      (S.onbFull, Icons.back_hand_rounded, 1.0),
      (S.onbHint, Icons.lightbulb_rounded, 0.7),
      (S.onbOwn, Icons.person_rounded, 0.45),
    ];
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 200, maxWidth: 290),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < steps.length; i++)
            Padding(
              padding: EdgeInsetsDirectional.only(start: i * 28.0, bottom: 10),
              child: Reveal(
                index: i,
                child: Container(
                  padding: const EdgeInsetsDirectional.fromSTEB(10, 8, 16, 8),
                  decoration: BoxDecoration(
                    color: Palette.surface,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: Palette.line),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: Palette.brand.withValues(alpha: steps[i].$3),
                          shape: BoxShape.circle,
                        ),
                        child: Icon(steps[i].$2, color: Colors.white, size: 18),
                      ),
                      const SizedBox(width: 10),
                      Flexible(child: BiText(steps[i].$1, size: 16, color: Palette.ink)),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
