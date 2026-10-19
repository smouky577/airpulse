# AirPulse

Android camera app: heart rate from skin color, and air movement in front of a monitor (BOS). A separate Windows app, **GridScreen**, shows the checkerboard for air mode.

Android приложение: пулс от цвета на кожата и въздух пред монитор (BOS). Отделно Windows приложение **GridScreen** показва шахматната решетка за въздушния режим.

## Pulse / Пулс

Works best on the **neck** (or face), in good light, with the phone still. The wrist is a weak signal with a camera.

Най-добре работи на **шията** (или лицето), на светло, с неподвижен телефон. Китката дава слаб сигнал.

Heart rate often rises with stress. That can look like an improvised “lie detector”, but **it is not one**: pulse also changes with talking, movement, coffee, heat, and nerves. Do not treat the number as proof of anything.

Пулсът често се качва при стрес. Затова някои го ползват като импровизиран „детектор на лъжата“, но **не е такъв**: числото се мени и от говорене, движение, кафе, топлина и нерви. Не го ползвай като доказателство.

## Requirements / Изисквания

- Android 8.0+, 64-bit phone (`arm64-v8a`)
- Windows 10 + Python 3 for GridScreen
- Android SDK / NDK if you build the APK yourself

## Phone / Телефон

Ready APK: `dist/AirPulse.apk` (also on [Releases](https://github.com/smouky577/airpulse/releases)). Sideload it; it is not on Play Store.

Готовият APK е в `dist/AirPulse.apk` и в Releases. Инсталира се ръчно.

Source: `airpulse-android/`. Build:

```
airpulse-android\build-apk.bat
```

## Monitor grid / Решетка за монитора

```
gridscreen-windows\GridScreen.bat
```

Fullscreen (F11), **horizontal lines**, cell **8 px**. If the camera shows rainbow swirls, press 8 or 9. The grid does not need to fill the camera; standing farther back (50–80 cm) leaves room for the air. The app crops to the screen. Air must pass **between** the phone and the monitor.

Цял екран (F11), **хоризонтални линии**, клетка **8 px**. Ако камерата вижда дъга/спирала, натисни 8 или 9. Решетката не трябва да пълни кадъра — по-назад (50–80 см) има място за въздуха. Приложението изрязва монитора. Въздухът минава **между** телефона и екрана.

## Layout / Структура

- `airpulse-android/` — Android app
- `airpulse-phone/` — shared C++ core (pulse and BOS)
- `gridscreen-windows/` — Windows checkerboard
- `reference/` and `evm_prototype.py` — algorithm prototype
- `tests/` — desktop tests for the core
