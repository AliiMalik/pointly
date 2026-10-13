package pk.rehnuma.app.voice

/** Fixed phrases (SPEC 8.3). Spoken from the Urdu text, captioned in the chosen script. */
enum class Phrase(val roman: String, val urdu: String) {
    ASK_GOAL("Bataiye, kya karna hai?", "بتائیے، کیا کرنا ہے؟"),
    THINKING("Ek second, dekh rahi hoon.", "ایک سیکنڈ، دیکھ رہی ہوں۔"),
    SENSITIVE_PAUSE("Yeh screen aap khud bharein. Main ise nahi dekhti.", "یہ اسکرین آپ خود بھریں۔ میں اسے نہیں دیکھتی۔"),
    DEVIATION("Koi baat nahi, ab yahan se chalte hain.", "کوئی بات نہیں، اب یہاں سے چلتے ہیں۔"),
    WRONG_TAP("Yeh nahi, jahan haath hai wahan dabayein.", "یہ نہیں، جہاں ہاتھ ہے وہاں دبائیں۔"),
    DONE("Shabash! Kaam ho gaya.", "شاباش! کام ہو گیا۔"),
    DIDNT_HEAR("Maaf kijiye, samajh nahi aaya. Dobara bataiye.", "معاف کیجیے، سمجھ نہیں آیا۔ دوبارہ بتائیے۔"),
    NETWORK_SLOW("Internet aahista hai, dobara koshish kar rahi hoon.", "انٹرنیٹ آہستہ ہے، دوبارہ کوشش کر رہی ہوں۔"),
    CANT_HELP("Is kaam mein main abhi madad nahi kar sakti.", "اس کام میں میں ابھی مدد نہیں کر سکتی۔"),
    YOUR_TURN("Ab aap khud karein, main dekh rahi hoon.", "اب آپ خود کریں، میں دیکھ رہی ہوں۔"),
}

/** Quick tasks (SPEC 8.4); goals are sent to the planner as typed. */
object QuickTasks {
    val ALL = listOf(
        "WhatsApp pe photo bhejni hai",
        "Phone ke likhai bari karni hai",
        "WiFi on karna hai",
        "Kisi ko call karni hai",
        "Mobile load karna hai",
    )
}
