import 'package:flutter/material.dart';

import '../channel/rehnuma_channel.dart';
import '../l10n/strings.dart';
import '../widgets/bilingual.dart';

/// Language, backend, voice, talk mode, demo mode, learning reset and debug overlay (SPEC 10 item 4).
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
    final c = await RehnumaChannel.getConfig();
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
    await RehnumaChannel.setConfig({key: value});
  }

  Future<void> _saveServer() async {
    await RehnumaChannel.setConfig({'baseUrl': _baseUrl.text, 'appKey': _appKey.text});
    _toast(S.setSaved);
  }

  Future<void> _check() async {
    setState(() {
      _checking = true;
      _checkResult = null;
    });
    await RehnumaChannel.setConfig({'baseUrl': _baseUrl.text, 'appKey': _appKey.text});
    final r = await RehnumaChannel.checkBackend();
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
              padding: const EdgeInsets.all(16),
              children: [
                _Choice(
                  title: S.setLanguage,
                  value: Lang.code.value,
                  options: const {Lang.en: S.setEnglish, Lang.ur: S.setUrdu},
                  onChanged: (v) {
                    Lang.set(v);
                    _set('language', v);
                  },
                ),
                _Section(
                  children: [
                    BiText(S.setBaseUrl, size: 18),
                    TextField(
                      controller: _baseUrl,
                      keyboardType: TextInputType.url,
                      style: const TextStyle(fontSize: 18),
                      decoration: const InputDecoration(hintText: 'http://192.168.1.10:8000'),
                    ),
                    const SizedBox(height: 12),
                    BiText(S.setAppKey, size: 18),
                    TextField(controller: _appKey, style: const TextStyle(fontSize: 18)),
                    const SizedBox(height: 12),
                    BigButton(label: S.setSave, icon: Icons.save_rounded, onPressed: _saveServer, minHeight: 56),
                    const SizedBox(height: 8),
                    BigButton(
                      label: S.setCheck,
                      icon: Icons.wifi_tethering_rounded,
                      color: Colors.white,
                      foreground: Theme.of(context).colorScheme.primary,
                      minHeight: 56,
                      onPressed: _checking ? null : _check,
                    ),
                    if (_checking) const Padding(padding: EdgeInsets.all(8), child: LinearProgressIndicator()),
                    if (_checkResult != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: Text(
                          _checkResult!,
                          style: TextStyle(
                            fontSize: 16,
                            color: _checkGood ? Theme.of(context).colorScheme.primary : const Color(0xFFB3261E),
                          ),
                        ),
                      ),
                  ],
                ),
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
                _Section(
                  children: [
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: BiText(S.setDemoMode, size: 18),
                      value: _config['demoMode'] == true,
                      onChanged: (v) => _set('demoMode', v),
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: BiText(S.setDebugBoxes, size: 18),
                      value: _config['debugBoxes'] == true,
                      onChanged: (v) async {
                        setState(() => _config['debugBoxes'] = v);
                        await RehnumaChannel.setDebugBoxes(v);
                      },
                    ),
                    const SizedBox(height: 8),
                    BigButton(
                      label: S.setResetLearning,
                      icon: Icons.restart_alt_rounded,
                      color: Colors.white,
                      foreground: const Color(0xFFB3261E),
                      minHeight: 56,
                      onPressed: () async {
                        await RehnumaChannel.resetLearning();
                        _toast(S.setResetDone);
                      },
                    ),
                  ],
                ),
              ],
            ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.symmetric(vertical: 8),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
        ),
      );
}

class _Choice extends StatelessWidget {
  const _Choice({
    required this.title,
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final Bi title;
  final String value;
  final Map<String, Bi> options;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return _Section(
      children: [
        BiText(title, size: 18),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          style: const ButtonStyle(minimumSize: WidgetStatePropertyAll(Size(0, 56))),
          segments: [
            for (final e in options.entries)
              ButtonSegment(value: e.key, label: Text(e.value.text, textAlign: TextAlign.center)),
          ],
          selected: {value},
          onSelectionChanged: (s) => onChanged(s.first),
        ),
      ],
    );
  }
}
