import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../theme/pointly_theme.dart';
import '../widgets/bilingual.dart';
import '../widgets/language_picker.dart';
import '../widgets/ui.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _baseUrl = TextEditingController();
  final _appKey = TextEditingController();
  Map<String, dynamic> _config = {};
  bool _loading = true;
  bool _checking = false;
  String? _checkResult;
  bool _checkGood = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _baseUrl.dispose();
    _appKey.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    Map<String, dynamic> c = {};
    try {
      c = await PointlyChannel.getConfig();
    } catch (_) {}
    if (!mounted) return;
    setState(() {
      _config = c;
      _baseUrl.text = (c['baseUrl'] as String?) ?? '';
      _appKey.text = (c['appKey'] as String?) ?? '';
      _loading = false;
    });
  }

  Future<void> _set(String key, Object value) async {
    setState(() => _config[key] = value);
    await PointlyChannel.setConfig({key: value});
  }

  Future<void> _saveServer() async {
    await PointlyChannel.setConfig({'baseUrl': _baseUrl.text, 'appKey': _appKey.text});
    _toast(S.setSaved);
  }

  Future<void> _check() async {
    setState(() {
      _checking = true;
      _checkResult = null;
    });
    Map<String, dynamic> r;
    try {
      await PointlyChannel.setConfig({'baseUrl': _baseUrl.text, 'appKey': _appKey.text});
      r = await PointlyChannel.checkBackend();
    } catch (e) {
      r = {'reachable': false, 'error': '$e'};
    }
    if (!mounted) return;
    final Bi msg;
    if (r['reachable'] != true) {
      msg = S.setCheckDown;
    } else if (r['keyOk'] != true) {
      msg = S.setCheckBadKey;
    } else {
      msg = S.setCheckOk;
    }
    final detail = r['reachable'] == true ? 'model: ${r['model'] ?? '?'}' : '${r['error'] ?? ''}';
    setState(() {
      _checking = false;
      _checkGood = r['reachable'] == true && r['keyOk'] == true;
      _checkResult = [msg.text, detail].join('\n');
    });
  }

  void _toast(Bi text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text.text)));
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    return Scaffold(
      appBar: AppBar(title: Text(S.setTitle.text)),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
              children: [
                SectionLabel(S.setLanguage),
                LanguagePicker(
                  onChanged: (v) {
                    Lang.set(v);
                    _set('language', v);
                  },
                ),
                const SizedBox(height: 28),
                SectionLabel(S.setSectionVoice),
                _Group(
                  children: [
                    _Choice(
                      title: S.setVoiceIn,
                      value: _config['voiceIn'] as String? ?? 'device',
                      options: const {'device': S.setDevice, 'cloud': S.setCloud},
                      onChanged: (v) => _set('voiceIn', v),
                    ),
                    _Choice(
                      title: S.setVoiceOut,
                      value: _config['voiceOut'] as String? ?? 'device',
                      options: const {'device': S.setDevice, 'cloud': S.setCloud},
                      onChanged: (v) => _set('voiceOut', v),
                    ),
                    _Choice(
                      title: S.setTalkMode,
                      value: _config['talkMode'] as String? ?? 'hold',
                      options: const {'hold': S.setHold, 'tap': S.setTap},
                      onChanged: (v) => _set('talkMode', v),
                    ),
                  ],
                ),
                const SizedBox(height: 28),
                SectionLabel(S.setSectionHelp),
                _Group(
                  children: [
                    _Toggle(
                      title: S.setDemoMode,
                      value: _config['demoMode'] == true,
                      onChanged: (v) => _set('demoMode', v),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: SizedBox(
                        width: double.infinity,
                        child: BigButton(
                          label: S.setResetLearning,
                          icon: Icons.restart_alt_rounded,
                          tone: ButtonTone.danger,
                          minHeight: 54,
                          onPressed: () async {
                            try {
                              await PointlyChannel.resetLearning();
                              _toast(S.setResetDone);
                            } catch (e) {
                              _toast(Bi('$e', '$e'));
                            }
                          },
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 28),
                SectionLabel(S.setSectionServer),
                _Group(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _FieldLabel(S.setBaseUrl),
                          TextField(
                            controller: _baseUrl,
                            keyboardType: TextInputType.url,
                            textDirection: TextDirection.ltr,
                            autocorrect: false,
                            style: const TextStyle(fontSize: 18),
                            decoration: const InputDecoration(
                              hintText: 'http://192.168.1.10:8000',
                              hintTextDirection: TextDirection.ltr,
                            ),
                          ),
                          const SizedBox(height: 16),
                          _FieldLabel(S.setAppKey),
                          TextField(
                            controller: _appKey,
                            textDirection: TextDirection.ltr,
                            autocorrect: false,
                            enableSuggestions: false,
                            style: const TextStyle(fontSize: 18),
                          ),
                          const SizedBox(height: 18),
                          BigButton(
                            label: S.setCheck,
                            icon: Icons.wifi_tethering_rounded,
                            minHeight: 56,
                            busy: _checking,
                            onPressed: _check,
                          ),
                          const SizedBox(height: 10),
                          BigButton(
                            label: S.setSave,
                            tone: ButtonTone.secondary,
                            minHeight: 54,
                            onPressed: _saveServer,
                          ),
                          SmoothSize(
                            child: _checkResult == null
                                ? const SizedBox(width: double.infinity)
                                : Semantics(
                                    liveRegion: true,
                                    child: Container(
                                      key: const ValueKey('check-result'),
                                      width: double.infinity,
                                      margin: const EdgeInsets.only(top: 14),
                                      padding: const EdgeInsets.all(14),
                                      decoration: BoxDecoration(
                                        color: _checkGood ? Palette.brandSoft : Palette.dangerSoft,
                                        borderRadius: BorderRadius.circular(Radii.sm),
                                      ),
                                      child: Row(
                                        crossAxisAlignment: CrossAxisAlignment.start,
                                        children: [
                                          Icon(
                                            _checkGood ? Icons.check_circle_rounded : Icons.error_outline_rounded,
                                            color: _checkGood ? Palette.brand : Palette.danger,
                                          ),
                                          const SizedBox(width: 10),
                                          Expanded(
                                            child: Text(
                                              _checkResult!,
                                              style: TextStyle(
                                                fontSize: 16,
                                                height: 1.4,
                                                color: _checkGood ? Palette.brandDeep : Palette.danger,
                                              ),
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 28),
                SectionLabel(S.setSectionDeveloper),
                _Group(
                  children: [
                    ListTile(
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                      title: BiText(S.homeMetrics, size: 17, color: Palette.ink),
                      trailing: const Icon(Icons.chevron_right_rounded, color: Palette.inkFaint),
                      onTap: () => Navigator.of(context).pushNamed('/metrics'),
                    ),
                    _Toggle(
                      title: S.setDebugBoxes,
                      value: _config['debugBoxes'] == true,
                      onChanged: (v) async {
                        setState(() => _config['debugBoxes'] = v);
                        await PointlyChannel.setDebugBoxes(v);
                      },
                    ),
                  ],
                ),
              ],
            ),
    );
  }
}

class _Group extends StatelessWidget {
  const _Group({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      semanticContainer: false,
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < children.length; i++) ...[
            if (i > 0) const Divider(indent: 16, endIndent: 16),
            children[i],
          ],
        ],
      ),
    );
  }
}

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.text);

  final Bi text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: BiText(text, size: 16, color: Palette.ink),
  );
}

class _Toggle extends StatelessWidget {
  const _Toggle({required this.title, required this.value, required this.onChanged});

  final Bi title;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      title: BiText(title, size: 17, color: Palette.ink),
      value: value,
      onChanged: onChanged,
    );
  }
}

class _Choice extends StatelessWidget {
  const _Choice({required this.title, required this.value, required this.options, required this.onChanged});

  final Bi title;
  final String value;
  final Map<String, Bi> options;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          BiText(title, size: 17, color: Palette.ink),
          const SizedBox(height: 10),
          SegmentedButton<String>(
            showSelectedIcon: false,
            segments: [
              for (final e in options.entries)
                ButtonSegment(
                  value: e.key,
                  label: Text(e.value.text, textAlign: TextAlign.center),
                ),
            ],
            selected: {value},
            onSelectionChanged: (s) => onChanged(s.first),
          ),
        ],
      ),
    );
  }
}
