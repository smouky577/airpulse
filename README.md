# AirPulse

Телефонно приложение за Android: пулс от камерата (цвят на кожата) и въздух пред монитор (BOS). Отделно Windows приложение **GridScreen** показва шахматната решетка за въздушния режим.

## Изисквания

- Android 8.0+, 64-битов телефон (`arm64-v8a`)
- Windows 10 + Python 3 за GridScreen
- Android SDK / NDK, ако ще компилираш APK-то сам

## Телефон

Готовият APK е в `dist/AirPulse.apk`. Инсталира се ръчно (не е в Play Store).

Изходен код: `airpulse-android/`. Сглобяване:

```
airpulse-android\build-apk.bat
```

## Решетка за монитора

```
gridscreen-windows\GridScreen.bat
```

Цял екран (F11), клетка 8 px за начало. Камерата гледа решетката; въздухът (дъх, газ) минава между телефона и екрана.

## Структура

- `airpulse-android/` — Android приложение
- `airpulse-phone/` — общо C++ ядро (пулс и BOS)
- `gridscreen-windows/` — решетка за Windows
- `reference/` и `evm_prototype.py` — прототип на алгоритмите
- `tests/` — настолни тестове на ядрото
