package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserTest {
    private fun match(s: String): DeviceAction {
        val r = LocalCommandParser.parse(s)
        assertTrue("expected Match for '$s' but was $r", r is ParseResult.Match)
        return (r as ParseResult.Match).action
    }

    @Test fun callNormalizesNumber() =
        assertEquals(DeviceAction.Dial("+923001234567"), match("call +92 300 1234567"))

    @Test fun callInvalidNumberIsRejectedNotGuessed() {
        assertTrue(LocalCommandParser.parse("call abc; rm -rf") is ParseResult.Invalid)
        assertTrue(LocalCommandParser.parse("dial 12") is ParseResult.Invalid)
    }

    @Test fun urduCall() =
        assertEquals(DeviceAction.Dial("03001234567"), match("03001234567 کو کال کرو"))

    @Test fun smsKeepsBodyAndNumber() =
        assertEquals(DeviceAction.PrepareSms("03001234567", "I will be late"), match("Send SMS to 03001234567 saying I will be late"))

    @Test fun smsBodyIsCapped() {
        val a = match("text 03001234567 " + "x".repeat(900)) as DeviceAction.PrepareSms
        assertEquals(Validators.MAX_SMS_BODY, a.body.length)
    }

    @Test fun alarmHandles24hAndAmPm() {
        assertEquals(DeviceAction.SetAlarm(7, 30), match("set alarm for 7:30"))
        assertEquals(DeviceAction.SetAlarm(19, 30), match("set an alarm at 7:30 pm"))
        assertEquals(DeviceAction.SetAlarm(0, 5), match("wake me up at 12:05 am"))
        assertEquals(DeviceAction.SetAlarm(6, 0), match("set alarm for 6"))
    }

    @Test fun alarmInvalidTimeRejected() {
        assertTrue(LocalCommandParser.parse("set alarm for 25:99") is ParseResult.Invalid)
        assertTrue(LocalCommandParser.parse("set alarm for 7:75") is ParseResult.Invalid)
    }

    @Test fun volumeDirections() {
        assertEquals(DeviceAction.Volume(VolumeDirection.UP), match("volume up"))
        assertEquals(DeviceAction.Volume(VolumeDirection.DOWN), match("volume down"))
        assertEquals(DeviceAction.Volume(VolumeDirection.UP), match("please increase the volume"))
        assertEquals(DeviceAction.Volume(VolumeDirection.DOWN), match("lower volume"))
    }

    @Test fun mediaCommands() {
        assertEquals(DeviceAction.Media(MediaCommand.PAUSE), match("pause the music"))
        assertEquals(DeviceAction.Media(MediaCommand.NEXT), match("next song"))
        assertEquals(DeviceAction.Media(MediaCommand.PLAY), match("play music"))
    }

    @Test fun readOnlyInfoIsNotSensitive() {
        val t = match("what time is it")
        val d = match("what is the date")
        val b = match("what's my battery")
        assertEquals(DeviceAction.GetTime, t)
        assertEquals(DeviceAction.GetDate, d)
        assertEquals(DeviceAction.GetBattery, b)
        assertTrue(listOf(t, d, b).none { it.sensitive })
    }

    @Test fun settingsScreens() {
        assertEquals(DeviceAction.OpenSettings(SettingsScreen.WIFI), match("open wifi settings"))
        assertEquals(DeviceAction.OpenSettings(SettingsScreen.BLUETOOTH), match("open bluetooth"))
        assertEquals(DeviceAction.OpenSettings(SettingsScreen.MAIN), match("open settings"))
        assertEquals(DeviceAction.OpenSettings(SettingsScreen.BATTERY), match("open battery settings"))
    }

    @Test fun urduPhrases() {
        assertEquals(DeviceAction.OpenSettings(SettingsScreen.WIFI), match("وائی فائی کھولو"))
        assertEquals(DeviceAction.GetTime, match("وقت کیا ہے"))
    }

    @Test fun openAppKeepsOriginalCase() =
        assertEquals(DeviceAction.OpenApp("YouTube"), match("open YouTube"))

    @Test fun unknownTextIsNoMatchAndEmptyIsNoMatch() {
        assertEquals(ParseResult.NoMatch, LocalCommandParser.parse("tell me a joke"))
        assertEquals(ParseResult.NoMatch, LocalCommandParser.parse("   "))
    }

    @Test fun everyDeviceChangingActionIsSensitive() {
        val sensitive = listOf(
            match("call 03001234567"), match("text 03001234567 hi"), match("set alarm for 7"),
            match("volume up"), match("pause the music"), match("open settings"), match("open YouTube"),
        )
        assertTrue(sensitive.all { it.sensitive })
        assertTrue(match("call 03001234567").needsOwnerAuth)
        assertTrue(match("text 03001234567 hi").needsOwnerAuth)
    }
}
