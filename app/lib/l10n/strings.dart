/// All user-visible strings in one place (SPEC 14) so the team can fix Urdu wording quickly.
/// Every string has an Urdu primary line and an English secondary line (SPEC 10).
class Bi {
  const Bi(this.ur, this.en);

  final String ur;
  final String en;
}

class QuickTask {
  const QuickTask(this.goal, this.ur, this.en);

  /// Roman Urdu goal text sent to the planner.
  final String goal;
  final String ur;
  final String en;
}

class S {
  S._();

  static const appName = Bi('رہنما', 'Rehnuma');

  // Onboarding (SPEC 10 item 1)
  static const onb1Title = Bi('رہنما آپ کو راستہ دکھاتی ہے', 'Rehnuma shows you the way');
  static const onb1Body = Bi(
    'بلبلے کو دبا کر بتائیے کیا کرنا ہے۔ رہنما ہاتھ سے بتائے گی کہاں دبانا ہے۔',
    'Hold the bubble and say what you want to do. Rehnuma points at what to press.',
  );
  static const onb2Title = Bi('رہنما کے تین وعدے', "Rehnuma's three promises");
  static const promise1 = Bi('میں خود کچھ نہیں دباتی', 'Main khud kuch nahi dabati');
  static const promise1En = 'I never tap anything for you';
  static const promise2 = Bi('صرف ضرورت پر دیکھتی ہوں', 'Sirf zaroorat pe dekhti hoon');
  static const promise2En = 'I only look when you ask for help';
  static const promise3 = Bi('PIN والی اسکرین نہیں دیکھتی', 'PIN wali screen nahi dekhti');
  static const promise3En = 'I never look at PIN or password screens';
  static const onb3Title = Bi('آپ خود سیکھیں گے', 'You will learn yourself');
  static const onb3Body = Bi(
    'آپ ہر بار خود دبائیں گے۔ دوسری تیسری بار رہنما کم مدد کرے گی تاکہ آپ سیکھ جائیں۔',
    'You always tap yourself. Each time you repeat a task, Rehnuma helps a little less.',
  );
  static const next = Bi('آگے', 'Next');
  static const start = Bi('شروع کریں', 'Start');

  // Permissions (SPEC 10 item 2)
  static const permTitle = Bi('اجازتیں', 'Permissions');
  static const permAccessibility = Bi('رہنما سروس آن کریں', 'Turn on the Rehnuma accessibility service');
  static const permAccessibilityHelp = Bi(
    'اگر سیٹنگ بند ہو: App info → ⋮ → Allow restricted settings',
    'Agar setting band ho: App info → ⋮ → Allow restricted settings.',
  );
  static const permMicrophone = Bi('مائیک کی اجازت', 'Microphone');
  static const permNotifications = Bi('نوٹیفکیشن کی اجازت', 'Notifications');
  static const permGrant = Bi('اجازت دیں', 'Allow');
  static const permOpen = Bi('سیٹنگ کھولیں', 'Open settings');
  static const permDone = Bi('ہو گیا', 'Done');
  static const permContinue = Bi('آگے چلیں', 'Continue');

  // Home (SPEC 10 item 3)
  static const homeStart = Bi('رہنما چالو کریں', 'Rehnuma chalu karein');
  static const homeStop = Bi('رہنما بند کریں', 'Rehnuma band karein');
  static const homeQuickTasks = Bi('جلدی والے کام', 'Quick tasks');
  static const homeState = Bi('حالت', 'Status');
  static const homeServiceOff = Bi(
    'پہلے رہنما سروس آن کریں',
    'Turn on the Rehnuma accessibility service first',
  );
  static const homeSettings = Bi('سیٹنگز', 'Settings');
  static const homeMetrics = Bi('پیمائش', 'Metrics');
  static const homePermissions = Bi('اجازتیں', 'Permissions');

  // Settings (SPEC 10 item 4)
  static const setTitle = Bi('سیٹنگز', 'Settings');
  static const setBaseUrl = Bi('سرور کا پتہ', 'Backend base URL');
  static const setAppKey = Bi('ایپ کی', 'App key');
  static const setVoiceIn = Bi('آواز سننا', 'Voice in');
  static const setVoiceOut = Bi('آواز بولنا', 'Voice out');
  static const setDevice = Bi('فون', 'Device');
  static const setCloud = Bi('کلاؤڈ', 'Cloud');
  static const setCaption = Bi('لکھائی', 'Caption script');
  static const setRoman = Bi('رومن اردو', 'Roman Urdu');
  static const setUrdu = Bi('اردو', 'Urdu script');
  static const setTalkMode = Bi('بات کرنے کا طریقہ', 'Talk mode');
  static const setHold = Bi('دبا کر رکھیں', 'Hold to talk');
  static const setTap = Bi('ٹیپ کریں', 'Tap to talk');
  static const setDemoMode = Bi('ڈیمو موڈ: ہمیشہ پوری مدد', 'Demo mode: hamesha poori madad');
  static const setResetLearning = Bi('سیکھنا ری سیٹ کریں', 'Seekhna reset karein');
  static const setResetDone = Bi('سیکھنا صاف ہو گیا', 'Learning cleared');
  static const setDebugBoxes = Bi('ڈیبگ خانے دکھائیں', 'Debug boxes overlay');
  static const setSave = Bi('محفوظ کریں', 'Save');
  static const setSaved = Bi('محفوظ ہو گیا', 'Saved');

  // Metrics (SPEC 10 item 5)
  static const metTitle = Bi('پیمائش', 'Metrics');
  static const metSessions = Bi('سیشن', 'Sessions');
  static const metSuccessRate = Bi('کامیابی', 'Success rate');
  static const metPlannerMs = Bi('پلانر وقت (درمیانہ)', 'Median planner ms');
  static const metStepMs = Bi('قدم کا وقت (درمیانہ)', 'Median step time ms');
  static const metCacheRate = Bi('کیش سے', 'Cache hit rate');
  static const metCachedMs = Bi('کیش والا قدم (درمیانہ)', 'Median cached step ms');
  static const metSensitive = Bi('PIN اسکرین پر رکی', 'Sensitive pauses');
  static const metReadMs = Bi('اسکرین پڑھنے کا وقت', 'Median screen read ms');
  static const metSettleToPointer = Bi('پوائنٹر آنے کا وقت', 'Median settle → pointer ms');
  static const metSteps = Bi('قدم', 'Steps');
  static const metExport = Bi('metrics.jsonl بھیجیں', 'Export metrics.jsonl');
  static const metDebug = Bi('ڈیبگ', 'Debug');
  static const metSaveSnapshot = Bi('موجودہ اسکرین محفوظ کریں', 'Save current screen snapshot');
  static const metSnapshotHint = Bi(
    '۵ سیکنڈ میں جس ایپ کی اسکرین چاہیے وہ کھولیں',
    'Open the target app within 5 seconds; Rehnuma saves its screen.',
  );
  static const metRefresh = Bi('تازہ کریں', 'Refresh');

  static const quickTasks = <QuickTask>[
    QuickTask('WhatsApp pe photo bhejni hai', 'واٹس ایپ پر تصویر بھیجنی ہے', 'Send a photo on WhatsApp'),
    QuickTask('Phone ke likhai bari karni hai', 'فون کی لکھائی بڑی کرنی ہے', 'Make the phone text bigger'),
    QuickTask('WiFi on karna hai', 'وائی فائی آن کرنا ہے', 'Turn on WiFi'),
    QuickTask('Kisi ko call karni hai', 'کسی کو کال کرنی ہے', 'Call someone'),
    QuickTask('Mobile load karna hai', 'موبائل لوڈ کرنا ہے', 'Buy mobile load'),
  ];

  /// Plain-language names for session states shown on Home.
  static Bi stateLabel(String state) {
    switch (state) {
      case 'IDLE':
        return const Bi('تیار ہے', 'Ready');
      case 'LISTENING':
        return const Bi('سن رہی ہوں', 'Listening');
      case 'TRANSCRIBING':
        return const Bi('سمجھ رہی ہوں', 'Transcribing');
      case 'READING_SCREEN':
        return const Bi('اسکرین دیکھ رہی ہوں', 'Reading the screen');
      case 'SENSITIVE_PAUSE':
        return const Bi('PIN اسکرین، رکی ہوئی ہوں', 'Paused on a sensitive screen');
      case 'PLANNING':
        return const Bi('سوچ رہی ہوں', 'Planning');
      case 'POINTING':
        return const Bi('بتا رہی ہوں', 'Pointing');
      case 'WAITING_FOR_USER':
        return const Bi('آپ کا انتظار', 'Waiting for you');
      case 'ASKING':
        return const Bi('سوال پوچھا ہے', 'Asked a question');
      case 'COMPLETED':
        return const Bi('کام ہو گیا', 'Completed');
      case 'ERROR':
        return const Bi('مسئلہ ہوا', 'Error');
      default:
        return Bi(state, state);
    }
  }
}
