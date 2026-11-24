-keepclassmembers class ai.lumen.assistant.LumenBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class ai.lumen.assistant.LumenVoiceService { *; }
-keep class ai.lumen.assistant.LumenVoiceSessionService { *; }
-keep class ai.lumen.assistant.LumenVoiceSession { *; }
-keep class ai.lumen.assistant.LumenRecognitionService { *; }
-keep class ai.lumen.assistant.LumenNotificationListener { *; }
