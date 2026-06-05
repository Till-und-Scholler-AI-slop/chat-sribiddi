# Chat Sribiddi Mobile

Native Flutter client for Chat Sribiddi.

The current implementation mirrors the Nuxt frontend as a mobile-first app:

- Chat screen with conversation list state, model picker, web-search toggle,
  suggestion chips, message bubbles, token/cost metadata and a bottom composer.
- Model catalog with provider filters, search, favorite toggles and BYOK-only
  key-required affordances.
- Model comparison screen with multiple panels and keep-to-chat behavior.
- Usage and settings screens that match the web app's BYOK-first product
  surface.
- Adaptive layout: compact screens use bottom navigation; wide desktop windows
  use a persistent sidebar with the chat history.
- German and English UI strings are routed through `AppStrings` instead of
  being embedded in widgets.

## Run locally

```bash
cd Frontend/mobile
flutter pub get
flutter run
```

For a desktop preview:

```bash
flutter build macos --debug
flutter run -d macos
```

For an Android debug build:

```bash
flutter build apk --debug
```

The backend-backed OAuth/session/chat streaming integration is intentionally
left as the next native integration step. The UI currently uses local demo
state so mobile and desktop layouts can be reviewed without backend setup.
