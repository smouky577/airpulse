# AirPulse

## Български

Android приложение: пулс от цвета на кожата и движение на въздух пред монитор (BOS). Отделно Windows програма, **GridScreen**, показва решетката за въздушния режим.

### Пулс

Най-добре работи на **шията** (или лицето), на светло, с неподвижен телефон. Има и режим **Пръст** — пръстът покрива камерата. Китката дава слаб сигнал.

Пулсът често се качва при стрес. Затова някои го ползват като импровизиран „детектор на лъжата“, но **не е такъв**: числото се мени и от говорене, движение, кафе, топлина и нерви. Не го ползвай като доказателство. Не е медицински уред.

### Изисквания

- Android 8.0 или по-нов, 64-битов телефон (`arm64-v8a`)
- Windows 10 и Python 3 за GridScreen
- Android SDK / NDK, ако сам компилираш APK

### Телефон

Готовият файл е `dist/AirPulse.apk` и в [Releases](https://github.com/smouky577/airpulse/releases). Инсталира се ръчно. Няма го в Play Store.

Кодът е в `airpulse-android/`. Компилиране с двоен клик върху:

```
airpulse-android\build-apk.bat
```

Готовият APK се копира в `dist\AirPulse.apk`.

### Windows

Файлът за стартиране е **`gridscreen-windows\GridScreen.bat`**.

Стартира се с **двоен клик** върху `GridScreen.bat`. Нужен е инсталиран Python 3 (командата `python` да се отваря от командния ред).

Цял екран с **F11**. По подразбиране са хоризонтални линии, клетка 8 px. Ако камерата вижда дъга или спирала, натисни 8 или 9. Клавиши: Esc затваря, F11 превключва целия екран, 1–9 размер на клетката, L линии, C шахмат, T точки, I обръщане.

Решетката не трябва да пълни кадъра. Телефонът стои на 50–80 см, за да има място за въздуха. Приложението изрязва монитора. Въздухът минава **между** телефона и екрана.

### Структура

- `airpulse-android/` — Android приложение
- `airpulse-phone/` — общо C++ ядро (пулс и BOS)
- `gridscreen-windows/` — Windows решетка (`GridScreen.bat`)
- `bos_pattern.html` — решетка в браузъра, ако не ползваш GridScreen
- `reference/` и `evm_prototype.py` — прототип на алгоритъма
- `tests/` — настолни тестове на ядрото

---

## English

Android camera app: heart rate from skin color, and air movement in front of a monitor (BOS). A separate Windows program, **GridScreen**, shows the pattern for air mode.

### Pulse

Works best on the **neck** (or face), in good light, with the phone still. There is also a **Finger** mode: the finger covers the camera. The wrist is a weak signal.

Heart rate often rises with stress. That can look like an improvised “lie detector”, but **it is not one**: pulse also changes with talking, movement, coffee, heat, and nerves. Do not treat the number as proof of anything. This is not a medical device.

### Requirements

- Android 8.0 or newer, 64-bit phone (`arm64-v8a`)
- Windows 10 and Python 3 for GridScreen
- Android SDK / NDK if you build the APK yourself

### Phone

Ready file: `dist/AirPulse.apk`, also on [Releases](https://github.com/smouky577/airpulse/releases). Sideload it. It is not on the Play Store.

Source: `airpulse-android/`. Build with a double click on:

```
airpulse-android\build-apk.bat
```

The finished APK is copied to `dist\AirPulse.apk`.

### Windows

The launcher file is **`gridscreen-windows\GridScreen.bat`**.

Start it with a **double click** on `GridScreen.bat`. Python 3 must be installed (`python` has to run from a command prompt).

Fullscreen with **F11**. Default pattern is horizontal lines, cell 8 px. If the camera shows rainbow swirls, press 8 or 9. Keys: Esc closes, F11 toggles fullscreen, 1–9 sets the cell size, L lines, C checkerboard, T dots, I invert.

The grid does not need to fill the frame. Stand 50–80 cm back so there is room for the air. The app crops to the monitor. Air must pass **between** the phone and the screen.

### Layout

- `airpulse-android/` — Android app
- `airpulse-phone/` — shared C++ core (pulse and BOS)
- `gridscreen-windows/` — Windows grid (`GridScreen.bat`)
- `bos_pattern.html` — browser grid if you are not using GridScreen
- `reference/` and `evm_prototype.py` — algorithm prototype
- `tests/` — desktop tests for the core
