import 'package:flutter/material.dart';

import '../l10n/strings.dart';
import '../theme/pointly_theme.dart';
import 'ui.dart';

class LanguagePicker extends StatelessWidget {
  const LanguagePicker({super.key, required this.onChanged});

  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    return Column(
      children: [
        _Option(code: Lang.en, label: S.setEnglish.en, tag: 'Aa', onTap: onChanged),
        const SizedBox(height: 10),
        _Option(code: Lang.ur, label: S.setUrdu.ur, tag: 'اب', onTap: onChanged),
      ],
    );
  }
}

class _Option extends StatelessWidget {
  const _Option({required this.code, required this.label, required this.tag, required this.onTap});

  final String code;
  final String label;
  final String tag;
  final ValueChanged<String> onTap;

  @override
  Widget build(BuildContext context) {
    final selected = Lang.code.value == code;
    return Semantics(
      selected: selected,
      inMutuallyExclusiveGroup: true,
      child: AnimatedContainer(
        duration: Motion.of(context, Motion.base),
        curve: Motion.enter,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(Radii.md + 2),
          border: Border.all(color: selected ? Palette.brand : Colors.transparent, width: 2),
        ),
        child: Pressable(
          onTap: () => onTap(code),
          radius: Radii.md,
          color: selected ? Palette.brandSoft : Palette.surface,
          border: !selected,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          child: Row(
            children: [
              ExcludeSemantics(
                child: Container(
                  width: 40,
                  height: 40,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: selected ? Palette.brand : Palette.paper,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    tag,
                    textScaler: TextScaler.noScaling,
                    textDirection: code == Lang.ur ? TextDirection.rtl : TextDirection.ltr,
                    style: TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      color: selected ? Colors.white : Palette.inkSoft,
                      height: code == Lang.ur ? 1.5 : 1.1,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  label,
                  textDirection: code == Lang.ur ? TextDirection.rtl : TextDirection.ltr,
                  textAlign: Directionality.of(context) == TextDirection.ltr ? TextAlign.left : TextAlign.right,
                  style: TextStyle(
                    fontSize: 19,
                    fontWeight: FontWeight.w700,
                    color: selected ? Palette.brandDeep : Palette.ink,
                    height: code == Lang.ur ? 1.6 : 1.2,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              AnimatedOpacity(
                opacity: selected ? 1 : 0,
                duration: Motion.of(context, Motion.fast),
                child: const Icon(Icons.check_circle_rounded, color: Palette.brand, size: 22),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
