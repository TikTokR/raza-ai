RAZA AI - FIXED ANDROID PROJECT

اہم:
- یہ ZIP مکمل Android Studio / GitHub repository project ہے۔
- ZIP extract کرنے کے بعد اس کے اندر موجود RAZA_AI_Complete_Android_FIXED فولڈر کے اندر کی تمام فائلیں GitHub repository کے ROOT میں upload کریں۔
- ZIP کو repository میں رکھ کر workflow چلانے کی ضرورت نہیں۔
- GitHub Actions workflow پہلے Java 17، Gradle 8.9 اور Android SDK 35 چیک کرتا ہے۔
- Unit test task app module کے لیے درست کیا گیا ہے: :app:testDebugUnitTest
- Java اور Kotlin دونوں کا JVM target 17 ہے۔

APK:
GitHub > Actions > کامیاب Build run > Artifacts > RAZA-AI-DEBUG-APK

Package: com.raza.ai
Min Android: 8.0 (API 26)
Compile / Target SDK: 35

نوٹ:
اس ماحول میں Android SDK اور Gradle distribution دستیاب نہ ہونے کی وجہ سے حقیقی APK build یہاں execute نہیں کیا جا سکا۔ GitHub Actions workflow میں build اور test دونوں شامل ہیں؛ کامیابی صرف GitHub run کے سبز ہونے اور APK artifact بننے کے بعد confirm ہوگی۔
Wake listener Android کی background microphone پابندیوں کے تابع ہے اور dedicated hardware hotword engine نہیں ہے۔
