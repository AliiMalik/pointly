package pk.rehnuma.app.voice

import pk.rehnuma.app.config.AppConfig

/** One sentence in both languages; the user's language setting picks what is shown and spoken. */
data class Line(val en: String, val ur: String) {
    fun pick(language: String): String = if (language == AppConfig.LANG_UR) ur else en
}

/** Fixed phrases (SPEC 8.3). */
enum class Phrase(val en: String, val ur: String) {
    ASK_GOAL("What would you like to do?", "بتائیے، کیا کرنا ہے؟"),
    THINKING("One moment, let me look.", "ایک سیکنڈ، دیکھ رہی ہوں۔"),
    SENSITIVE_PAUSE("Please fill in this screen yourself. I don't read it.", "یہ اسکرین آپ خود بھریں۔ میں اسے نہیں دیکھتی۔"),
    DEVIATION("No problem, let's carry on from here.", "کوئی بات نہیں، اب یہاں سے چلتے ہیں۔"),
    WRONG_TAP("Not there. Tap where the hand is pointing.", "یہ نہیں، جہاں ہاتھ ہے وہاں دبائیں۔"),
    DONE("Well done! All finished.", "شاباش! کام ہو گیا۔"),
    DIDNT_HEAR("Sorry, I didn't catch that. Please say it again.", "معاف کیجیے، سمجھ نہیں آیا۔ دوبارہ بتائیے۔"),
    NETWORK_SLOW("The internet is slow, trying again.", "انٹرنیٹ آہستہ ہے، دوبارہ کوشش کر رہی ہوں۔"),
    CANT_HELP("Sorry, I can't help with this task yet.", "اس کام میں میں ابھی مدد نہیں کر سکتی۔"),
    YOUR_TURN("Now you try, I'm watching.", "اب آپ خود کریں، میں دیکھ رہی ہوں۔"),
    ;

    val line: Line get() = Line(en, ur)
}

/** Quick tasks (SPEC 8.4). The English goal is sent to the planner; the label follows the language. */
object QuickTasks {
    val ALL = listOf(
        Line("Send a photo on WhatsApp", "واٹس ایپ پر تصویر بھیجنی ہے"),
        Line("Make the text on my phone bigger", "فون کی لکھائی بڑی کرنی ہے"),
        Line("Turn on WiFi", "وائی فائی آن کرنا ہے"),
        Line("Call someone", "کسی کو کال کرنی ہے"),
        Line("Top up my mobile balance", "موبائل لوڈ کرنا ہے"),
    )
}
