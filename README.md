# Clinical Deck (private offline build)

A standalone Android study app for the privately exported Internal Medicine corpus. It has no internet permission, accounts, analytics, or external services.

## Features

- Dashboard, seven-source library, chapter hierarchy, full-corpus local search, and filters
- Study mode with immediate answer reveal and explanations
- Exam mode with deferred score
- Custom session size, source, chapter, and difficulty
- Bookmarks, local progress/accuracy, and basic expanding-interval review scheduling
- Light/dark palette and completely offline corpus
- HTML-rich source text is converted for native display; source question/media references remain in the compressed corpus

## Reproducible build

```bash
export JAVA_HOME="$HOME/.local/jdk/jdk-17"
export ANDROID_HOME="$HOME/android-sdk"
python3 tools/build_corpus.py
python3 -m unittest -v tests/test_corpus.py
./gradlew testDebugUnitTest assembleDebug
```

The corpus builder reads `../export/books/*/all.json` and writes a compact gzip asset. Do not commit or publish the generated corpus or APK.

## Privacy

Application state uses private Android SharedPreferences. The manifest deliberately omits network and backup permissions. Credentials, authentication state, and tokens are neither read by the builder nor bundled into the app.
