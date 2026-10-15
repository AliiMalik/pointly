import 'package:flutter/widgets.dart';

class Lang {
  Lang._();

  static const en = 'en';
  static const ur = 'ur';

  static final ValueNotifier<String> code = ValueNotifier(en);

  static bool get isUrdu => code.value == ur;

  static void set(String? value) => code.value = value == ur ? ur : en;

  static void watch(BuildContext context) => context.dependOnInheritedWidgetOfExactType<LangScope>();

  static TextDirection get direction => isUrdu ? TextDirection.rtl : TextDirection.ltr;
}

class LangScope extends InheritedNotifier<ValueNotifier<String>> {
  LangScope({super.key, required super.child}) : super(notifier: Lang.code);
}

class Bi {
  const Bi(this.en, this.ur);

  final String en;
  final String ur;

  String get text => Lang.isUrdu ? ur : en;
}

class S {
  S._();

  static const appName = 'Pointly';

  static const onbLanguage = Bi('Choose your language', 'اپنی زبان چنیں');
  static const onb1Title = Bi('Pointly shows you the way', 'پوائنٹلی آپ کو راستہ دکھاتی ہے');
  static const onb1Body = Bi(
    'Hold the bubble and say what you want to do. Pointly points at what to tap.',
    'بلبلے کو دبا کر بتائیے کیا کرنا ہے۔ پوائنٹلی ہاتھ سے بتائے گی کہاں دبانا ہے۔',
  );
  static const onb2Title = Bi("Pointly's three promises", 'پوائنٹلی کے تین وعدے');
  static const promise1 = Bi('I never tap anything for you', 'میں خود کچھ نہیں دباتی');
  static const promise2 = Bi('I only look when you ask for help', 'صرف ضرورت پر دیکھتی ہوں');
  static const promise3 = Bi('I never look at PIN or password screens', 'PIN والی اسکرین نہیں دیکھتی');
  static const onb3Title = Bi('You learn by doing', 'آپ خود سیکھیں گے');
  static const onb3Body = Bi(
    'You always tap yourself. Each time you repeat a task, Pointly helps a little less.',
    'آپ ہر بار خود دبائیں گے۔ دوسری تیسری بار پوائنٹلی کم مدد کرے گی تاکہ آپ سیکھ جائیں۔',
  );
  static const onbBack = Bi('Back', 'واپس');
  static Bi onbStep(int n, int of) => Bi('Step $n of $of', 'قدم $n از $of');
  static const onbFull = Bi('Full help', 'پوری مدد');
  static const onbHint = Bi('A short hint', 'چھوٹا اشارہ');
  static const onbOwn = Bi('On your own', 'خود سے');
  static const next = Bi('Next', 'آگے');
  static const start = Bi('Start', 'شروع کریں');

  static const permTitle = Bi('Permissions', 'اجازتیں');
  static const permAccessibility = Bi('Turn on the Pointly accessibility service', 'پوائنٹلی سروس آن کریں');
  static const permAccessibilityHelp = Bi(
    'If the switch is greyed out: App info → ⋮ → Allow restricted settings.',
    'اگر سوئچ آن نہ ہو رہا ہو (ہلکا نظر آئے): App info → ⋮ → Allow restricted settings',
  );
  static const permHeader = Bi('Get Pointly ready', 'پوائنٹلی کو تیار کریں');
  static const permSub = Bi(
    'Three quick steps. You can change them later in Settings.',
    'تین آسان قدم۔ بعد میں سیٹنگز میں بدل سکتے ہیں۔',
  );
  static const permAccessibilityBody = Bi(
    'Lets Pointly read the labels on your screen. It never taps for you.',
    'اس سے پوائنٹلی اسکرین پر لکھی چیزیں پڑھ سکتی ہے۔ یہ آپ کی جگہ کچھ نہیں دباتی۔',
  );
  static const permMicrophoneBody = Bi('So you can say what you need.', 'تاکہ آپ بول کر بتا سکیں۔');
  static const permNotificationsBody = Bi(
    'Shows a Stop button while Pointly is on.',
    'جب پوائنٹلی چل رہی ہو تو اسے بند کرنے کا بٹن دکھاتی ہے۔',
  );
  static const permMicrophone = Bi('Microphone', 'مائیک کی اجازت');
  static const permNotifications = Bi('Notifications', 'نوٹیفکیشن کی اجازت');
  static const permGrant = Bi('Allow', 'اجازت دیں');
  static const permOpen = Bi('Open settings', 'سیٹنگ کھولیں');
  static const permDone = Bi('Done', 'ہو گیا');
  static const permContinue = Bi('Continue', 'آگے چلیں');

  static const homeStart = Bi('Start Pointly', 'پوائنٹلی چالو کریں');
  static const homeStop = Bi('Stop Pointly', 'پوائنٹلی بند کریں');
  static const homeOnTitle = Bi('Pointly is on', 'پوائنٹلی چل رہی ہے');
  static const homeOffTitle = Bi('Pointly is off', 'پوائنٹلی بند ہے');
  static const homeOnBody = Bi(
    'Hold the green bubble on any screen and speak. Tap it once to pick a task.',
    'کسی بھی اسکرین پر سبز بلبلہ دبا کر رکھیں اور بولیں۔ کام چننے کے لیے ایک بار دبائیں۔',
  );
  static const homeOffBody = Bi(
    'Start it to bring the helper bubble onto your screen.',
    'شروع کریں تاکہ مددگار بلبلہ آپ کی اسکرین پر آ جائے۔',
  );
  static const homeNow = Bi('Right now', 'اس وقت');
  static const homeIdleHint = Bi(
    'Hold the bubble and say what you would like to do.',
    'بلبلہ دبا کر رکھیں اور بتائیں کیا کرنا ہے۔',
  );
  static const homeSetUp = Bi('Set up', 'سیٹ اپ کریں');
  static const homeQuickTasks = Bi('Quick tasks', 'جلدی والے کام');
  static const homeState = Bi('Status', 'حالت');
  static const homeServiceOff = Bi('Turn on the Pointly accessibility service first', 'پہلے پوائنٹلی سروس آن کریں');
  static const homeSettings = Bi('Settings', 'سیٹنگز');
  static const homeMetrics = Bi('Metrics', 'پیمائش');
  static const homePermissions = Bi('Permissions', 'اجازتیں');

  static const setTitle = Bi('Settings', 'سیٹنگز');
  static const setLanguage = Bi('Language', 'زبان');
  static const setEnglish = Bi('English', 'English');
  static const setUrdu = Bi('اردو', 'اردو');
  static const setSectionVoice = Bi('Voice', 'آواز');
  static const setSectionServer = Bi('Server', 'سرور');
  static const setSectionHelp = Bi('Help and learning', 'مدد اور سیکھنا');
  static const setSectionDeveloper = Bi('Developer', 'ڈویلپر');
  static const setBaseUrl = Bi('Backend address', 'سرور کا پتہ');
  static const setAppKey = Bi('App key', 'ایپ کی');
  static const setVoiceIn = Bi('Listening', 'آواز سننا');
  static const setVoiceOut = Bi('Speaking', 'آواز بولنا');
  static const setDevice = Bi('Phone', 'فون');
  static const setCloud = Bi('Cloud', 'کلاؤڈ');
  static const setTalkMode = Bi('Talk mode', 'بات کرنے کا طریقہ');
  static const setHold = Bi('Hold to talk', 'دبا کر رکھیں');
  static const setTap = Bi('Tap to talk', 'ٹیپ کریں');
  static const setDemoMode = Bi('Demo mode: always give full help', 'ڈیمو موڈ: ہمیشہ پوری مدد');
  static const setResetLearning = Bi('Reset learning', 'سیکھنا ری سیٹ کریں');
  static const setResetDone = Bi('Learning cleared', 'سیکھنا صاف ہو گیا');
  static const setDebugBoxes = Bi('Show debug boxes', 'ڈیبگ خانے دکھائیں');
  static const setSave = Bi('Save', 'محفوظ کریں');
  static const setCheck = Bi('Check server', 'سرور چیک کریں');
  static const setCheckOk = Bi('Server OK', 'سرور ٹھیک ہے');
  static const setCheckBadKey = Bi('Server found, but the app key is wrong', 'سرور ملا، مگر ایپ کی غلط ہے');
  static const setCheckDown = Bi('Server not reachable', 'سرور نہیں ملا');
  static const setSaved = Bi('Saved', 'محفوظ ہو گیا');

  static const metTitle = Bi('Metrics', 'پیمائش');
  static const metNote = Bi(
    'These numbers stay on this phone. No screen text is saved.',
    'یہ اعداد صرف اس فون پر رہتے ہیں۔ اسکرین کی کوئی لکھائی محفوظ نہیں ہوتی۔',
  );
  static const metSessions = Bi('Sessions', 'سیشن');
  static const metSuccessRate = Bi('Success rate', 'کامیابی');
  static const metPlannerMs = Bi('Median planner time (ms)', 'پلانر وقت (درمیانہ)');
  static const metStepMs = Bi('Median step time (ms)', 'قدم کا وقت (درمیانہ)');
  static const metCacheRate = Bi('Cache hit rate', 'کیش سے');
  static const metCachedMs = Bi('Median cached step (ms)', 'کیش والا قدم (درمیانہ)');
  static const metSensitive = Bi('Sensitive-screen pauses', 'PIN اسکرین پر رکی');
  static const metReadMs = Bi('Median screen read (ms)', 'اسکرین پڑھنے کا وقت');
  static const metSettleToPointer = Bi('Median settle → pointer (ms)', 'پوائنٹر آنے کا وقت');
  static const metSteps = Bi('Steps', 'قدم');
  static const metExport = Bi('Export metrics.jsonl', 'metrics.jsonl بھیجیں');
  static const metDebug = Bi('Debug', 'ڈیبگ');
  static const metSaveSnapshot = Bi('Save current screen snapshot', 'موجودہ اسکرین محفوظ کریں');
  static const metSnapshotHint = Bi(
    'Open the app you want within 5 seconds; Pointly saves its screen.',
    '۵ سیکنڈ میں جس ایپ کی اسکرین چاہیے وہ کھولیں',
  );
  static const metRefresh = Bi('Refresh', 'تازہ کریں');

  static const quickTasks = <Bi>[
    Bi('Send a photo on WhatsApp', 'واٹس ایپ پر تصویر بھیجنی ہے'),
    Bi('Make the text on my phone bigger', 'فون کی لکھائی بڑی کرنی ہے'),
    Bi('Turn on WiFi', 'وائی فائی آن کرنا ہے'),
    Bi('Call someone', 'کسی کو کال کرنی ہے'),
    Bi('Top up my mobile balance', 'موبائل لوڈ کرنا ہے'),
  ];

  static Bi stateLabel(String state) {
    switch (state) {
      case 'IDLE':
        return const Bi('Ready', 'تیار ہے');
      case 'LISTENING':
        return const Bi('Listening', 'سن رہی ہوں');
      case 'TRANSCRIBING':
        return const Bi('Understanding', 'سمجھ رہی ہوں');
      case 'READING_SCREEN':
        return const Bi('Reading the screen', 'اسکرین دیکھ رہی ہوں');
      case 'SENSITIVE_PAUSE':
        return const Bi('Paused on a private screen', 'PIN اسکرین، رکی ہوئی ہوں');
      case 'PLANNING':
        return const Bi('Thinking', 'سوچ رہی ہوں');
      case 'POINTING':
        return const Bi('Showing you', 'بتا رہی ہوں');
      case 'WAITING_FOR_USER':
        return const Bi('Waiting for you', 'آپ کا انتظار');
      case 'ASKING':
        return const Bi('Asked a question', 'سوال پوچھا ہے');
      case 'COMPLETED':
        return const Bi('Done', 'کام ہو گیا');
      case 'ERROR':
        return const Bi('Something went wrong', 'مسئلہ ہوا');
      default:
        return Bi(state, state);
    }
  }
}
