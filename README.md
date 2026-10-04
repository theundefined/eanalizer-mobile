# eAnalizer Mobile

Natywna aplikacja Android (Kotlin, Jetpack Compose / Material 3) do analizy zużycia energii elektrycznej
na podstawie godzinowych danych licznikowych z portalu Enea eBOK. To mobilny port
[eanalizer](https://github.com/theundefined/eanalizer).

## Funkcje

- Logowanie do Enea eBOK (z obsługą kodu SMS/e-mail) i automatyczne pobieranie godzinowych danych CSV.
- Koszty energii w taryfach G11, G12, G12w i innych (edytowalna tabela cen i opłat).
- Symulacja magazynu energii (pojemność, sprawność) oraz net-meteringu (współczynnik 0,7 / 0,8).
- Porównanie wszystkich taryf i wskazanie najtańszej.
- Analiza z rynkowymi cenami RCE (API PSE).
- Obliczanie optymalnej pojemności magazynu.
- Podsumowania miesięczne i dzienne z wykresami, wykrywanie brakujących godzin.
- Eksport wyników (symulacja, agregaty dzienne i miesięczne) do CSV.
- Dane przechowywane lokalnie; dane logowania szyfrowane (EncryptedSharedPreferences).

## Budowanie

Wymagane: JDK 21 i Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug        # APK debug
./gradlew testDebugUnitTest    # testy jednostkowe
./gradlew spotlessApply        # formatowanie (ktfmt)
```

Wydania budowane są przez GitHub Actions po uruchomieniu `./release.sh [major|minor|patch|vX.Y.Z]`.

## Podziękowania

Logika analizy (taryfy, symulacja magazynu, net-metering, RCE) pochodzi z projektu
[eanalizer](https://github.com/theundefined/eanalizer).

## Zastrzeżenie

Aplikacja jest nieoficjalna i nie jest powiązana z Enea S.A. ani przez nią wspierana.
Wyniki mają charakter szacunkowy — rzeczywiste rozliczenia mogą się różnić.

## Licencja

GPL-3.0-or-later — zob. [LICENSE](LICENSE).
